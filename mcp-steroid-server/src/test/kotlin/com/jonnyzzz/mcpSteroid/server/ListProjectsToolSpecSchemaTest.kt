/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListProjectsToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = ListProjectsToolSpec { unreachableHandler() }
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_list_projects")
        assertRequiredExactly(spec.inputSchema)
    }

    @Test
    fun `response tolerates backend fields additively`() {
        // IdeInfo(name, version, build) — `build` has NO default, so it MUST be present; there is no `fullName`.
        val legacy = """{"ide":{"name":"x","version":"1","build":"x"},
            |"plugin":{"id":"p","name":"p","version":"1"},"pid":1,
            |"projects":[{"name":"n","path":"/p"}]}""".trimMargin()
        val decoded = McpJson.decodeFromString(ListProjectsResponse.serializer(), legacy)
        assertNull(decoded.projects.single().backend)
        assertTrue(decoded.backends.isEmpty())

        val withBackend = ProjectInfo(name = "n", path = "/p", backend = "pid-1234")
        assertEquals("pid-1234", withBackend.backend)
    }
}
