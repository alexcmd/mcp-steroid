/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.LocalFileSystem
import com.jonnyzzz.mcpSteroid.execution.ApplyPatchException
import com.jonnyzzz.mcpSteroid.execution.McpEditingGuardException
import com.jonnyzzz.mcpSteroid.execution.executeApplyPatch
import com.jonnyzzz.mcpSteroid.execution.mcpEditingGuard
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.successTextResult
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class ApplyPatchToolHandlerIJ : ProjectScopedToolHandler(), ApplyPatchToolHandler {
    private val log = thisLogger()

    override suspend fun applyPatch(projectName: String, applyPatchRequest: ApplyPatchRequest): ToolCallResult {
        val project = resolveProject(projectName)
        val hunks = applyPatchRequest.hunks
        val dryRun = applyPatchRequest.dryRun

        val executionId = project.executionStorage.writeToolCall(
            toolName = "steroid_apply_patch",
            arguments = McpJson.encodeToJsonElement(applyPatchRequest).jsonObject,
            taskId = applyPatchRequest.taskId,
        )

        // Run the whole patch under McpEditingGuard:
        //   1. dialog killer + modality fail-fast
        //   2. commit + saveAllDocuments + awaitRefresh BEFORE the patch
        //   3. memory/disk conflict resolver disabled for the body
        //   4. executeApplyPatch (write phase skipped when dryRun=true)
        //   5. awaitRefresh AFTER the patch
        // See McpEditingGuard KDoc for the full flow + threading rationale.
        // The guard wraps dry-run too: pre-refresh ensures preflight reads the
        // current on-disk state; post-refresh is a no-op cost when nothing
        // changed, traded for symmetry with the live path.
        val result = try {
            mcpEditingGuard().withEditingGuard(
                project = project,
                executionId = executionId,
                logMessage = { log.info(it) },
            ) {
                executeApplyPatch(project, hunks, dryRun = dryRun) { path ->
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
            // Return unexpected runtime failures as tool errors, not JSON-RPC 500.
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
