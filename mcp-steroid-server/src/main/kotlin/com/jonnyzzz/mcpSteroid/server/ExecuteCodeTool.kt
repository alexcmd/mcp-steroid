package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.enumString
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.int
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.mcp.withDefaultValue
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How `steroid_execute_code` treats IDE modality (modal dialogs / indexing) around the script.
 * One stance; everything finer is done from the script via [McpScriptContext] methods.
 */
@Serializable
enum class ModalMode {
    /**
     * Default, for PSI / code-management flows: close leftover modal dialogs (deepest-first), require a
     * non-modal IDE (fail with a screenshot if one survives), commit+save documents + refresh VFS, wait
     * for indexing (smart mode), then run with the dialog killer monitoring — a modal appearing mid-run is
     * closed and the run fails (with a thread dump + screenshot).
     */
    @SerialName("smart_non_modal")
    SMART_NON_MODAL,

    /**
     * Require a non-modal IDE at the start (fail with a screenshot if modal); do nothing else — no sweep,
     * no document sync, no smart-mode wait, no dialog killer. The script prepares what it needs via
     * [McpScriptContext] (`closeModalDialogs`, `syncDocuments`, `waitForSmartMode`, ...).
     */
    @SerialName("non_modal")
    NON_MODAL,

    /**
     * No sweep, no checks, no validation — the script runs against whatever IDE state exists, modal dialogs
     * included. For trivial / hardcoded IDE management only; NOT safe for PSI / code-management flows.
     */
    @SerialName("unleashed")
    UNLEASHED;

    companion object {
        val DEFAULT = SMART_NON_MODAL

        private val byWire = entries.associateBy { it.wire }

        fun fromWire(value: String?): ModalMode =
            value?.let { byWire[it] ?: throw com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException(
                "Unknown modal mode '$value'. Expected one of: ${entries.joinToString(", ") { it.wire }}.") }
                ?: DEFAULT
    }
}

/** JSON-schema wire name for this mode (the @SerialName value), e.g. "smart_non_modal". */
val ModalMode.wire: String
    get() = when (this) {
        ModalMode.SMART_NON_MODAL -> "smart_non_modal"
        ModalMode.NON_MODAL -> "non_modal"
        ModalMode.UNLEASHED -> "unleashed"
    }

@Serializable
data class ExecCodeParams(
    val taskId: String,
    val code: String,
    val reason: String,
    val timeout: Int,

    /** How to treat IDE modality around the script. See [ModalMode]. Default [ModalMode.SMART_NON_MODAL]. */
    val modal: ModalMode = ModalMode.DEFAULT,
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

    val modal = InputSchemaElement.param("modal")
        .description(
            "How to treat IDE modality around the script (default smart_non_modal). " +
                "'smart_non_modal': close leftover modal dialogs, require non-modal (fail+screenshot if one " +
                "survives), commit+save documents, refresh VFS, wait for indexing, and keep closing+failing " +
                "on any modal that appears while running — the safe choice for PSI / code-editing scripts. " +
                "'non_modal': only require a non-modal IDE at the start (fail+screenshot if modal); do nothing " +
                "else — prepare what you need from the script via context methods (closeModalDialogs, " +
                "syncDocuments, waitForSmartMode). 'unleashed': no sweep/checks/validation, run against " +
                "whatever state exists (modals included) — for trivial/hardcoded IDE actions only, not PSI."
        )
        .enumString(listOf(ModalMode.SMART_NON_MODAL.wire, ModalMode.NON_MODAL.wire, ModalMode.UNLEASHED.wire))
        .withDefaultValue(ModalMode.DEFAULT.wire)
        .registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val projectName = context[projectName]
        val code = context[code]
        val taskId = context[taskId]
        val reason = context[reason]
        val timeout = context[timeout]
        val modal = ModalMode.fromWire(context[modal])

        val execCodeParams = ExecCodeParams(
            taskId = taskId,
            code = code,
            reason = reason,
            timeout = timeout,
            modal = modal,
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
