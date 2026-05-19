/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.PromptsCapability
import com.jonnyzzz.mcpSteroid.mcp.PromptsContextProvider
import com.jonnyzzz.mcpSteroid.mcp.ResourcesCapability
import com.jonnyzzz.mcpSteroid.mcp.ServerCapabilities
import com.jonnyzzz.mcpSteroid.mcp.ServerInfo
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.ToolsCapability
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.server.ActionDiscoveryExecutor
import com.jonnyzzz.mcpSteroid.server.ApplyPatchExecutor
import com.jonnyzzz.mcpSteroid.server.ApplyPatchToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeExecutor
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackExecutor
import com.jonnyzzz.mcpSteroid.server.FetchResourceProjectValidator
import com.jonnyzzz.mcpSteroid.server.ListProjectsExecutor
import com.jonnyzzz.mcpSteroid.server.ListWindowsExecutor
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.McpSteroidExecutors
import com.jonnyzzz.mcpSteroid.server.McpToolRegistrations
import com.jonnyzzz.mcpSteroid.server.OpenProjectExecutor
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionInputExecutor
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

fun createProxyMcpServerCore(registry: ServerRegistry): McpServerCore {
    val core = McpServerCore(
        serverInfo = ServerInfo(
            name = "mcp-steroid-proxy",
            version = registry.config.version ?: "0.1.0",
        ),
        capabilities = ServerCapabilities(
            tools = ToolsCapability(listChanged = false),
            prompts = PromptsCapability(listChanged = false),
            resources = ResourcesCapability(subscribe = false, listChanged = false),
        ),
        instructions = "Proxy MCP server for MCP Steroid instances discovered from local IDE metadata.",
    )
    McpToolRegistrations(ProxyMcpSteroidExecutors(registry)).registerAll(core)
    return core
}

private class ProxyMcpSteroidExecutors(
    private val registry: ServerRegistry,
) : McpSteroidExecutors {
    override val listProjects = ListProjectsExecutor {
        registry.ensureFresh()
        handleAggregateProjects(registry, JsonObject(emptyMap())).toToolCallResult()
    }

    override val listWindows = ListWindowsExecutor {
        registry.ensureFresh()
        handleAggregateWindows(registry, JsonObject(emptyMap())).toToolCallResult()
    }

    override val executeCode = ExecuteCodeExecutor { _, args, progress ->
        delegateTool("steroid_execute_code", args, progress)
    }

    override val applyPatch = ApplyPatchExecutor { req ->
        delegateTool("steroid_apply_patch", req.toJsonObject())
    }

    override val executeFeedback = ExecuteFeedbackExecutor { _, params ->
        delegateTool("steroid_execute_feedback", params.arguments ?: JsonObject(emptyMap()))
    }

    override val actionDiscovery = ActionDiscoveryExecutor { _, args ->
        delegateTool("steroid_action_discovery", args)
    }

    override val visionScreenshot = VisionScreenshotExecutor { _, args, progress ->
        delegateTool("steroid_take_screenshot", args, progress)
    }

    override val visionInput = VisionInputExecutor { _, args, progress ->
        delegateTool("steroid_input", args, progress)
    }

    override val openProject = OpenProjectExecutor { req, progress ->
        delegateTool("steroid_open_project", req.toJsonObject(), progress)
    }

    override val fetchResourceProjectValidator = FetchResourceProjectValidator { projectName ->
        registry.ensureFresh()
        registry.validateProjectNameForProxy(projectName)?.let { errorResult(it) }
    }

    override val promptsContext = PromptsContextProvider {
        PromptsContext(productCode = "IU", baselineVersion = 253)
    }

    override val defaultExecuteCodeTimeoutSeconds: () -> Int = {
        600
    }

    private suspend fun delegateTool(
        toolName: String,
        args: JsonObject,
        progress: McpProgressReporter? = null,
    ): ToolCallResult {
        registry.ensureFresh()
        val resolved = registry.resolveServerForToolCall(toolName, args)
        if (resolved is RoutingResult.Error) return errorResult(resolved.message)

        val target = resolved as RoutingResult.Resolved
        val result = registry.callTool(target.serverId, target.toolName, args) { event ->
            val message = event["message"]?.jsonPrimitive?.contentOrNull
            if (!message.isNullOrBlank()) progress?.report(message)
        }
        return result.toToolCallResult()
    }

    private fun errorResult(message: String): ToolCallResult = ToolCallResult(
        content = listOf(ContentItem.Text(text = message)),
        isError = true,
    )
}

private fun JsonObject.toToolCallResult(): ToolCallResult =
    McpJson.decodeFromJsonElement(ToolCallResult.serializer(), this)

private fun ApplyPatchToolHandler.Request.toJsonObject(): JsonObject = buildJsonObject {
    put("project_name", projectName)
    put("task_id", taskId)
    putJsonArray("hunks") {
        for (hunk in hunks) {
            add(buildJsonObject {
                put("file_path", hunk.filePath)
                put("old_string", hunk.oldString)
                put("new_string", hunk.newString)
            })
        }
    }
}

private fun OpenProjectToolHandler.Request.toJsonObject(): JsonObject = buildJsonObject {
    put("project_path", projectPath)
    put("task_id", taskId)
    put("reason", reason)
    put("trust_project", trustProject)
}
