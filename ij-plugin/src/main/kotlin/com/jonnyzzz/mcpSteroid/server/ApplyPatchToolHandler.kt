/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.observation.Observation
import com.jonnyzzz.mcpSteroid.execution.ApplyPatchException
import com.jonnyzzz.mcpSteroid.execution.ApplyPatchHunk
import com.jonnyzzz.mcpSteroid.execution.ApplyPatchRequest
import com.jonnyzzz.mcpSteroid.execution.McpEditingGuardException
import com.jonnyzzz.mcpSteroid.execution.executeApplyPatch
import com.jonnyzzz.mcpSteroid.execution.mcpEditingGuard
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.mcp.successTextResult
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ApplyPatchToolDescriptionPromptArticle
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/**
 * First-class MCP tool for atomic multi-site literal-text patching.
 *
 * Surfaces the same underlying [executeApplyPatch] engine that
 * [com.jonnyzzz.mcpSteroid.execution.McpScriptContextImpl.applyPatch] uses,
 * but without the kotlinc compile step — so Claude Code's hard-coded ~60s
 * MCP tool timeout (issue #16837) no longer truncates 5+ hunk patches.
 *
 * Input shape mirrors the DSL:
 * ```json
 * {
 *   "project_name": "project-home",
 *   "task_id": "feature-x",
 *   "reason": "rename logger name across services",
 *   "hunks": [
 *     {"file_path": "/abs/A.java", "old_string": "foo", "new_string": "bar"},
 *     {"file_path": "/abs/A.java", "old_string": "baz", "new_string": "qux"},
 *     {"file_path": "/abs/B.java", "old_string": "log1", "new_string": "log2"}
 *   ]
 * }
 * ```
 *
 * Semantics: pre-flight validates every `old_string` is present exactly once;
 * all edits land as a single undoable [WriteCommandAction], PSI committed in
 * the same action, VFS async-refreshed on completion.
 */
class ApplyPatchToolSpec(val handler: ApplyPatchToolHandler) : McpTool {
    override val name = "steroid_apply_patch"
    override val description get() = ApplyPatchToolDescriptionPromptArticle().readPayload(buildPromptsContext())
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_name") {
                put("type", "string")
                put("description", "Project name (from steroid_list_projects)")
            }
            putJsonObject("task_id") {
                put("type", "string")
                put("description", "Your task identifier; reuse across related calls.")
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "One-line summary of what this patch changes.")
            }
            putJsonObject("hunks") {
                put("type", "array")
                put("description", "Literal-text hunks. Each hunk's old_string must occur exactly once in its file.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("file_path") {
                            put("type", "string")
                            put("description", "Absolute filesystem path. Matches Claude Code `Edit`'s `file_path` field.")
                        }
                        putJsonObject("old_string") {
                            put("type", "string")
                            put("description", "Literal text to replace (must occur exactly once).")
                        }
                        putJsonObject("new_string") {
                            put("type", "string")
                            put("description", "Replacement text.")
                        }
                    }
                    putJsonArray("required") {
                        add("file_path")
                        add("old_string")
                        add("new_string")
                    }
                }
            }
        }
        putJsonArray("required") {
            add("project_name")
            add("task_id")
            add("hunks")
        }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: project_name")
        args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: task_id")

        val rawHunks = args["hunks"]
            ?: return ToolCallResult.errorResult("Missing required parameter: hunks (array)")
        // Some MCP clients (Claude Code's tool-call envelope under certain
        // configurations) pass complex parameters as serialised JSON strings
        // instead of native arrays. `?.jsonArray` would have thrown
        // IllegalArgumentException on a JsonPrimitive, leaking a stacktrace
        // through the MCP layer. Detect-and-decode keeps the contract clean
        // (regression: ApplyPatchToolIntegrationTest#testStringEncodedHunksReturnsCleanError).
        val hunksJson = when (rawHunks) {
            is JsonArray -> rawHunks
            is JsonPrimitive -> {
                if (!rawHunks.isString) {
                    return ToolCallResult.errorResult("hunks must be a JSON array, got primitive: ${rawHunks.content}")
                }
                val decoded = try {
                    McpJson.parseToJsonElement(rawHunks.content)
                } catch (e: SerializationException) {
                    return ToolCallResult.errorResult("hunks must be a JSON array — failed to parse string-encoded value: ${e.message}")
                }
                (decoded as? JsonArray)
                    ?: return ToolCallResult.errorResult("hunks must be a JSON array — string-encoded value parsed to ${decoded::class.simpleName}")
            }
            else -> return ToolCallResult.errorResult("hunks must be a JSON array, got ${rawHunks::class.simpleName}")
        }
        if (hunksJson.isEmpty()) return ToolCallResult.errorResult("hunks array is empty")

        val hunks = hunksJson.mapIndexed { i, el ->
            val o = (el as? JsonObject)
                ?: return ToolCallResult.errorResult("hunks[$i] is not an object")
            val filePath = o["file_path"]?.jsonPrimitive?.contentOrNull
                ?: return ToolCallResult.errorResult("hunks[$i].file_path is required")
            val oldString = o["old_string"]?.jsonPrimitive?.contentOrNull
                ?: return ToolCallResult.errorResult("hunks[$i].old_string is required")
            val newString = o["new_string"]?.jsonPrimitive?.contentOrNull
                ?: return ToolCallResult.errorResult("hunks[$i].new_string is required")
            ApplyPatchHunk(filePath = filePath, oldString = oldString, newString = newString)
        }

        return handler.applyPatch(projectName, ApplyPatchRequest(hunks))
    }
}

interface ApplyPatchToolHandler {
    suspend fun applyPatch(projectName: String, applyPatchRequest: ApplyPatchRequest): ToolCallResult
}

@Service(Service.Level.APP)
class ApplyPatchToolHandlerIJ: ApplyPatchToolHandler {
    private val log = thisLogger()

    override suspend fun applyPatch(
        projectName: String,
        applyPatchRequest: ApplyPatchRequest
    ): ToolCallResult {
        val (project, availableNames) = readAction {
            val openProjects = getInstance().openProjects
            openProjects.find { it.name == projectName } to openProjects.map { it.name }
        }

        if (project == null) {
            return ToolCallResult.errorResult("Project not found: \"$projectName\". Available projects: $availableNames")
        }

        val hunks = applyPatchRequest.hunks

        val executionId = ExecutionId("apply-patch-${System.currentTimeMillis()}")

        // Run the whole patch under McpEditingGuard:
        //   1. dialog killer + modality fail-fast
        //   2. commit + saveAllDocuments + awaitRefresh BEFORE the patch
        //   3. memory-vs-disk conflict resolver disabled for the body
        //   4. executeApplyPatch
        //   5. awaitRefresh AFTER the patch
        // See McpEditingGuard KDoc for the full flow + threading rationale.
        val result = try {
            mcpEditingGuard().withEditingGuard(
                project = project,
                executionId = executionId,
                logMessage = { log.info(it) },
            ) {
                executeApplyPatch(project, hunks) { path ->
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                }
            }
        } catch (e: McpEditingGuardException) {
            log.warn("[apply_patch] editing guard rejected the call: ${e.message}", e)
            analyticsBeacon.capture(
                event = "apply_patch",
                project = project,
                properties = mapOf("result" to "modal-blocked"),
            )
            return ToolCallResult.errorResult(e.message ?: "MCP editing guard rejected the call")
        } catch (e: ApplyPatchException) {
            analyticsBeacon.capture(
                event = "apply_patch",
                project = project,
                properties = mapOf("result" to "error"),
            )
            return ToolCallResult.errorResult(e.message ?: "apply-patch failed with no message")
        } catch (e: ProcessCanceledException) {
            // Cancellation must propagate intact — never wrap or swallow.
            throw e
        } catch (e: RuntimeException) {
            // Unexpected runtime exceptions (VFS guards in test mode, indexing
            // races, write-action conflicts, etc.) must come back to the agent
            // as a tool-error with a useful message, NOT as a JSON-RPC 500. The
            // agent can re-issue or change strategy on a tool-error; a 500
            // looks like a transport failure and stalls the session.
            log.warn("[apply_patch] unexpected runtime failure: ${e.message}", e)
            analyticsBeacon.capture(
                event = "apply_patch",
                project = project,
                properties = mapOf("result" to "runtime-error"),
            )
            return ToolCallResult.errorResult("apply-patch failed: ${e.javaClass.simpleName}: ${e.message ?: "<no message>"}")
        }

        analyticsBeacon.capture(
            event = "apply_patch",
            project = project,
            properties = mapOf(
                "result" to "success",
                "hunks" to result.hunkCount.toString(),
                "files" to result.fileCount.toString(),
            ),
        )

        return ToolCallResult.successTextResult(result.toString())
    }
}
