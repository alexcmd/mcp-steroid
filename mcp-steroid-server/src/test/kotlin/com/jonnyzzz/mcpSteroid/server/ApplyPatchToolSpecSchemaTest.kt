/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class ApplyPatchToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = ApplyPatchToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_apply_patch")
        assertRequiredExactly(schema, "project_name", "task_id", "hunks")
        assertStringProperty(schema, "project_name")
        assertStringProperty(schema, "task_id")
        assertStringProperty(schema, "reason")
        assertBooleanProperty(schema, "dry_run")
        val hunkItem = assertArrayProperty(schema, "hunks").items()
        assertRequiredExactly(hunkItem, "file_path", "old_string", "new_string")
        assertStringProperty(hunkItem, "file_path")
        assertStringProperty(hunkItem, "old_string")
        assertStringProperty(hunkItem, "new_string")
    }
}
