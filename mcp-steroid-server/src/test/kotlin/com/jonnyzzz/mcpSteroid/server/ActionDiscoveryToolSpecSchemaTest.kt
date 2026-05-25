/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class ActionDiscoveryToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = ActionDiscoveryToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_action_discovery")
        assertRequiredExactly(schema, "project_name", "file_path")
        assertStringProperty(schema, "project_name")
        assertStringProperty(schema, "file_path")
        assertStringProperty(schema, "task_id")
        assertIntegerProperty(schema, "caret_offset")
        assertIntegerProperty(schema, "max_actions_per_group")
        assertArrayProperty(schema, "action_groups")
    }
}
