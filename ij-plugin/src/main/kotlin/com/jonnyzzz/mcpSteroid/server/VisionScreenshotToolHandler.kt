/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.serialization.json.*
import java.util.*

/**
 * Handler for the steroid_take_screenshot MCP tool.
 */
class VisionScreenshotToolHandler : McpTool {
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
            ?: return errorResult("Missing required parameter: project_name")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: task_id")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: reason")
        val windowId = args["window_id"]?.jsonPrimitive?.contentOrNull

        val project = readAction {
            ProjectManager.getInstance().openProjects.find { it.name == projectName }
        } ?: return errorResult("Project not found: $projectName")

        val executionId = project.executionStorage.writeToolCall(
            toolName = "steroid_take_screenshot",
            arguments = args,
            taskId = "screenshot-$taskId"
        )
        project.executionStorage.writeCodeExecutionData(executionId, "reason.txt", reason)

        val builder = ToolCallResult.builder()
        suspend fun log(message: String) {
            val text = message
            builder.addTextContent(text)
            context.mcpProgressReporter.report(text)
            project.executionStorage.appendExecutionEvent(executionId, text)
        }

        try {
            log("execution_id: ${executionId.executionId}")
            log("WARNING: Heavy endpoint. Prefer steroid_execute_code for regular automation.")

            val artifacts = VisionService.capture(project, executionId, windowId)
            val imageBase64 = Base64.getEncoder().encodeToString(artifacts.imageBytes)
            builder.addContent(ContentItem.Image(data = imageBase64, mimeType = "image/png"))

            artifacts.logMessages().forEach { log(it) }
        } catch (e: Exception) {
            val message = "Screenshot capture failed: ${e.message}"
            builder.addTextContent("ERROR: $message").markAsError()
            project.executionStorage.writeCodeErrorEvent(executionId, message)
        }

        return builder.build()
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = "ERROR: $message")),
        isError = true
    )
}
