/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListWindowsToolSpecSchemaTest {
    private val spec = ListWindowsToolSpec { unreachableHandler() }

    @Test
    fun `inputSchema is valid JSON Schema`() {
        assertToolSpecHasValidJsonSchema(spec)
    }

    @Test
    fun `tool identity`() {
        assertToolIdentity(spec, expectedName = "steroid_list_windows")
    }

    @Test
    fun `takes no parameters`() {
        val properties = spec.inputSchema["properties"] as JsonObject
        assertTrue(properties.isEmpty(), "properties must be empty for $properties")
        val required = spec.inputSchema["required"] as JsonArray
        assertEquals(0, required.size, "required must be empty for $required")
    }
}
