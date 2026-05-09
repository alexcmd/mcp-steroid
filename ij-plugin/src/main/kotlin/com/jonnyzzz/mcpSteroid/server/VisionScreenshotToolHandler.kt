/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.*

/**
 * Handler for the steroid_take_screenshot MCP tool.
 */
class VisionScreenshotToolSpec(val handler: VisionScreenshotToolHandler) : McpTool {
    override val name = "steroid_take_screenshot"

    override val description = """
        Capture a screenshot of the IDE and return an image payload.

        HEAVY ENDPOINT: This is intended for debugging and tricky configuration only.
        Prefer steroid_execute_code for regular automation.

        Use steroid_list_windows when multiple IDE windows are open and pass window_id to target a specific window.

        The screenshot and component tree are saved under the execution folder:
        - screenshot.png
        - screenshot-tree.md
        - screenshot-meta.json

        After execution, call steroid_execute_feedback to log your feedback.
    """.trimIndent()

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_name") {
                put("type", "string")
                put("description", "Project name (from steroid_list_projects)")
            }
            putJsonObject("task_id") {
                put("type", "string")
                put("description", "Your task identifier to group related executions.")
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "Reason for taking the screenshot. Required for audit logs.")
            }
            putJsonObject("window_id") {
                put("type", "string")
                put("description", "Optional window id from steroid_list_windows to target a specific IDE window.")
            }
        }
        putJsonArray("required") {
            add("project_name")
            add("task_id")
            add("reason")
        }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments
        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: project_name")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: task_id")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: reason")
        val windowId = args["window_id"]?.jsonPrimitive?.contentOrNull

        return handler.screenshotWindow(projectName, ScreenshotParams(taskId, reason, windowId), context.mcpProgressReporter)
    }
}

@Serializable
data class ScreenshotParams(
    val taskId: String,
    val reason: String,
    val windowId: String? = null
)

interface VisionScreenshotToolHandler {
    suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter
    ): ToolCallResult

}

@Service(Service.Level.APP)
class VisionScreenshotToolHandlerIJ : VisionScreenshotToolHandler {
    private val log = thisLogger()
    private val json = Json { encodeDefaults = true }

    override suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter
    ): ToolCallResult {
        val taskId = screenshotParams.taskId
        val reason = screenshotParams.reason

        val project = readAction {
            ProjectManager.getInstance().openProjects.find { it.name == projectName }
        } ?: return ToolCallResult.errorResult("Project not found: $projectName")

        val executionId = project.executionStorage.writeToolCall(
            toolName = "steroid_take_screenshot",
            arguments = json.encodeToJsonElement(screenshotParams).jsonObject,
            taskId = "screenshot-$taskId"
        )
        project.executionStorage.writeCodeExecutionData(executionId, "reason.txt", reason)

        val builder = ToolCallResult.builder()

        try {
            val artifacts = VisionService.capture(project, executionId, screenshotParams.windowId)
            val imageBase64 = Base64.getEncoder().encodeToString(artifacts.imageBytes)
            builder.addContent(ContentItem.Image(data = imageBase64, mimeType = "image/png"))

            artifacts.logMessages().forEach {
                log.info("Captured window artifact: $it")
                builder.addTextContent(it)
            }
        } catch (e: Exception) {
            val message = "Screenshot capture failed: ${e.message}"
            builder.addTextContent("ERROR: $message").markAsError()
            project.executionStorage.writeCodeErrorEvent(executionId, message)
        }

        return builder.build()
    }
}
