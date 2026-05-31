package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.boolean
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.int
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.mcp.withDefaultValue
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import kotlinx.serialization.Serializable

@Serializable
data class ExecCodeParams(
    val taskId: String,
    val code: String,
    val reason: String,
    val timeout: Int,

    /** If true, cancel execution when a modal dialog appears and return a screenshot. Default true. */
    val cancelOnModal: Boolean = true,

    /** Controls pre-execution dialog killer: null = use registry default, true = force enable, false = force disable. */
    val dialogKiller: Boolean = true,

    /**
     * If true, proceed even when a modal dialog is detected before the script runs: skip the
     * pre-flight fail-fast and skip (rather than hang on) the pre-flight commit/VFS refresh, with a
     * warning in the result. Default false (fail fast on an unexpected modal).
     */
    val allowModal: Boolean = false,
)

/**
 * Handler for the steroid_execute_code MCP tool.
 */
class ExecuteCodeToolSpec(val handler: () -> ExecuteCodeToolHandler) : McpToolBase() {
    override val name = "steroid_execute_code"
    override val description get() = ExecuteCodeToolDescriptionPromptArticle().readPayload(PromptsContext.Generic)

    val projectName = CommonToolParams.projectName().registerToSchema()

    val code = InputSchemaElement.param("code")
        .description("Kotlin suspend method body")
        .string()
        .required()
        .registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()

    val reason = CommonToolParams.reason().registerToSchema()

    private val defaultTimeoutSeconds = 600
    val timeout = InputSchemaElement.param("timeout")
        .description("Execution timeout in seconds (default: $defaultTimeoutSeconds, configurable via mcp.steroid.execution.timeout registry key)")
        .int()
        .withDefaultValue(defaultTimeoutSeconds)
        .registerToSchema()

    //TODO: Drop dialog killer
    val dialogKiller = InputSchemaElement.param("dialog_killer")
        .description("Override pre-execution dialog killer: true = force enable, false = force disable. Default: use registry setting (mcp.steroid.dialog.killer.enabled).")
        .boolean()
        .withDefaultValue(true)
        .registerToSchema()

    val allowModal = InputSchemaElement.param("allow_modal")
        .description("If true, proceed even when a modal dialog is detected before the script runs (skip the pre-flight fail-fast and skip the pre-flight commit/VFS refresh, with a warning). Default false.")
        .boolean()
        .withDefaultValue(false)
        .registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val projectName = context[projectName]
        val code = context[code]
        val taskId = context[taskId]
        val reason = context[reason]
        val timeout = context[timeout]
        val dialogKiller = context[dialogKiller]
        val allowModal = context[allowModal]

        val execCodeParams = ExecCodeParams(
            taskId = taskId,
            code = code,
            reason = reason,
            timeout = timeout,
            dialogKiller = dialogKiller,
            allowModal = allowModal,
        )

        return handler().executeCode(projectName, execCodeParams, context.mcpProgressReporter)
    }
}

interface ExecuteCodeToolHandler {
    suspend fun executeCode(
        projectName: String,
        execCodeParams: ExecCodeParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult
}
