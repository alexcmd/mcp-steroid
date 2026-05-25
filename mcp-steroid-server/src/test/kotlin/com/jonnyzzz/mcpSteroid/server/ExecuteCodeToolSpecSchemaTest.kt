/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class ExecuteCodeToolSpecSchemaTest {
    private val spec = ExecuteCodeToolSpec { unreachableHandler() }
    private val schema = spec.inputSchema

    @Test
    fun `inputSchema is valid JSON Schema`() {
        assertToolSpecHasValidJsonSchema(spec)
    }

    @Test
    fun `tool identity`() {
        assertToolIdentity(spec, expectedName = "steroid_execute_code")
    }

    @Test
    fun `required properties`() {
        assertRequiredExactly(schema, "project_name", "code", "reason", "task_id")
    }

    @Test
    fun `project_name is a string property`() {
        assertStringProperty(schema, "project_name")
    }

    @Test
    fun `code is a string property`() {
        assertStringProperty(schema, "code")
    }

    @Test
    fun `task_id is a string property`() {
        assertStringProperty(schema, "task_id")
    }

    @Test
    fun `reason is a string property`() {
        assertStringProperty(schema, "reason")
    }

    @Test
    fun `timeout is an optional integer property`() {
        assertIntegerProperty(schema, "timeout")
        assertOptional(schema, "timeout")
    }

    @Test
    fun `dialog_killer is an optional boolean property`() {
        assertBooleanProperty(schema, "dialog_killer")
        assertOptional(schema, "dialog_killer")
    }
}
