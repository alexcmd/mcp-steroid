/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.vision.InputSequenceParser
import com.jonnyzzz.mcpSteroid.vision.InputStep
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.serialization.json.*

/**
 * Handler for the steroid_input MCP tool.
 */
class VisionInputToolHandler : McpTool {
    override val name = "steroid_input"
    override val description = """
        Send input events (keyboard + mouse) to the IDE using a sequence string.

        HEAVY ENDPOINT: Intended for debugging only. Prefer steroid_execute_code for regular automation.

        Sequence format (comma-separated or newline-separated steps; commas optional with newlines):
        - stick:ALT           (hold a key until the end)
        - delay:400           (milliseconds)
        - press:CTRL+P        (press key with modifiers)
        - type:hello          (type text; commas are allowed unless they look like a new step)
        - click:CTRL+Left@120,200        (click with modifiers at screenshot coords)
        - click:Right@screen:400,300     (click at screen coords)

        Comma separators are detected by ", <step>:" patterns, so avoid typing ", delay:" etc.
        Trailing commas before a newline are ignored.
        Use "#" for comments until the end of the line.

        All keys are released at the end of the sequence.

        The input is delivered to the window captured by steroid_take_screenshot (window_id from metadata) and the focus is forced to that window.
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
                put("description", "Reason for sending input. Required for audit logs.")
            }
            putJsonObject("screenshot_execution_id") {
                put("type", "string")
                put("description", "Execution ID from steroid_take_screenshot (or takeIdeScreenshot() inside a script)")
            }
            putJsonObject("sequence") {
                put("type", "string")
                put("description", "Comma-separated input sequence (stick/press/type/click/delay)")
            }
        }
        putJsonArray("required") {
            add("project_name")
            add("task_id")
            add("reason")
            add("screenshot_execution_id")
            add("sequence")
        }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments ?: return errorResult("Missing arguments")
        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_name")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: task_id")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: reason")
        val screenshotExecutionId = args["screenshot_execution_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: screenshot_execution_id")
        val sequence = args["sequence"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: sequence")

        val project = readAction {
            ProjectManager.getInstance().openProjects.find { it.name == projectName }
        } ?: return errorResult("Project not found: $projectName")

        val executionId = project.executionStorage.writeToolCall(
            toolName = "steroid_input",
            arguments = args,
            taskId = "input-$taskId"
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
            log("Using screenshot execution: $screenshotExecutionId")

            val parsed = InputSequenceParser().parse(sequence)
            if (parsed.filterIsInstance<InputStep.Click>().any { it.target is com.jonnyzzz.mcpSteroid.vision.InputTarget.Unsupported }) {
                throw IllegalArgumentException("Unsupported target in sequence. Only screenshot/screen targets are supported.")
            }

            val meta = VisionService.loadScreenshotMeta(project, ExecutionId(screenshotExecutionId))
            if (meta.system != "swing") {
                throw IllegalStateException("Unsupported capture system '${meta.system}'. Only swing is supported.")
            }
            if (meta.windowId.isNullOrBlank()) {
                throw IllegalStateException("Screenshot metadata missing windowId; re-capture with the latest version.")
            }
            log("Using window_id: ${meta.windowId}")

            VisionService.executeInput(meta, parsed)
            log("Input sequence executed successfully.")
        } catch (e: Exception) {
            val message = "Input execution failed: ${e.message}"
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
