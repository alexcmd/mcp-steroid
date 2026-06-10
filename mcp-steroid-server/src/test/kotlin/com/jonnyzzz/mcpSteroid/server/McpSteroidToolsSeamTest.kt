/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpSteroidToolsSeamTest {
    // `spec()` is a PUBLIC accessor; openProjectToolSpec() is protected and must never be called
    // from outside a subclass. BackendNameTools overrides the protected seam but the test only ever
    // touches the public spec().
    private open class FakeTools : McpSteroidTools() {
        override fun <T> handler(type: Class<T>): T = error("not used")
        fun spec(): McpToolBase = openProjectToolSpec()
    }

    private class BackendNameTools : FakeTools() {
        override fun openProjectToolSpec(): McpToolBase =
            OpenProjectToolSpec(includeBackendName = true) { error("not used") }
    }

    @Test
    fun `default seam hides backend_name`() {
        val spec = FakeTools().spec() as OpenProjectToolSpec
        assertFalse(spec.includeBackendName)
    }

    @Test
    fun `override exposes backend_name`() {
        // Calls the PUBLIC spec(), which internally invokes the overridden protected seam.
        val spec = BackendNameTools().spec() as OpenProjectToolSpec
        assertTrue(spec.includeBackendName)
    }
}
