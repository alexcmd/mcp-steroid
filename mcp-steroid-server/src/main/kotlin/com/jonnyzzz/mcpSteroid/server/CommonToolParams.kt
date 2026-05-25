package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string

/**
 * Shared schema-element factories for parameters that recur across multiple `*ToolSpec`s.
 * Each factory returns a fully-built, required [InputSchemaElement]; callers chain
 * `.registerToSchema()` to attach it to their tool's input schema.
 */
object CommonToolParams {
    /** Required `project_name` used to dispatch a tool call to an already-open IDE project. */
    fun projectName(description: String = "Project name (from steroid_list_projects)") =
        InputSchemaElement.param("project_name")
            .description(description)
            .string()
            .required()

    /** Required `task_id` used to group related executions in audit logs. */
    fun taskId(description: String = "Your task identifier to group related executions.") =
        InputSchemaElement.param("task_id")
            .description(description)
            .string()
            .required()

    /** Required `reason` string with the audit-log convention: `Reason for $action. Required for audit logs.` */
    fun auditReason(action: String) =
        InputSchemaElement.param("reason")
            .description("Reason for $action. Required for audit logs.")
            .string()
            .required()
}
