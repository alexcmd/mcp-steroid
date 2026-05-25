package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.int
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.mcp.stringArray
import kotlinx.serialization.Serializable

/**
 * Handler for the steroid_action_discovery MCP tool.
 */
class ActionDiscoveryToolSpec(val handler: () -> ActionDiscoveryToolHandler) : McpToolBase() {
    override val name = "steroid_action_discovery"

    override val description =
        "Discover what IDE actions are available at a file location before invoking them via steroid_execute_code. " +
                "Use BEFORE applying quick-fixes, refactorings, or running gutter actions (Run/Debug) when you don't know the exact action ID. " +
                "Returns action IDs (pass to ActionManager.getAction(id) in exec_code), intention names, error fixes, and gutter icon actions. " +
                "Workflow: (1) call this with file + caret offset, (2) pick action from results, (3) invoke via steroid_execute_code."

    val projectName = CommonToolParams
        .projectName("Name of the project containing the file (from steroid_list_projects).")
        .registerToSchema()

    val filePath = InputSchemaElement.param("file_path")
        .description("Absolute path or project-relative path to the file.")
        .string()
        .required()
        .registerToSchema()

    val caretOffset = InputSchemaElement.param("caret_offset")
        .description("Caret offset within the file (default: 0).")
        .int()
        .registerToSchema()

    val actionGroups = InputSchemaElement.param("action_groups")
        .description("Optional list of action group IDs to expand (default: editor popup + gutter).")
        .stringArray()
        .registerToSchema()

    val maxActionsPerGroup = InputSchemaElement.param("max_actions_per_group")
        .description("Limit the number of actions returned per action group (default: 200).")
        .int()
        .registerToSchema()

    val taskId = InputSchemaElement.param("task_id")
        .description("Optional task ID for log grouping.")
        .string()
        .registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val projectName = context[projectName]
        val filePath = context[filePath]
        val caretOffset = context[caretOffset] ?: 0
        val actionGroups = context.params.arguments["action_groups"]?.let {
            context[actionGroups]
                .map { entry -> entry.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
        val maxActions = context[maxActionsPerGroup]?.coerceAtLeast(0) ?: 200
        val taskId = context[taskId]

        return handler().discoverActions(
            projectName,
            ActionDiscoveryParams(
                filePath = filePath,
                caretOffset = caretOffset,
                actionGroups = actionGroups,
                maxActions = maxActions,
                taskId = taskId
            )
        )
    }
}

@Serializable
data class ActionDiscoveryParams(
    val filePath: String,
    val caretOffset: Int,
    val actionGroups: List<String>?,
    val maxActions: Int,
    val taskId: String?
)


interface ActionDiscoveryToolHandler {
    suspend fun discoverActions(projectName: String, actionDiscoveryParams: ActionDiscoveryParams): ToolCallResult
}
