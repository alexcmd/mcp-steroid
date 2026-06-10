/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** R3.9: version-skew warnings go to STDERR (never stdout) and de-dupe per (pid, plugin version). */
class BackendVersionSkewTest {
    private fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val buf = ByteArrayOutputStream()
        System.setErr(PrintStream(buf, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buf.toString(Charsets.UTF_8)
    }

    @Test
    fun `warns on stderr when versions differ and de-dupes per pid+version`() {
        // A unique pid keeps the process-global de-dup set independent of other tests.
        val pid = 918_273_001L
        val stderr = captureStderr {
            BackendVersionSkew.warnIfSkewed(pid = pid, pluginVersion = "0.100", devrigVersion = "0.101")
            // Same (pid, version) again: must NOT warn a second time.
            BackendVersionSkew.warnIfSkewed(pid = pid, pluginVersion = "0.100", devrigVersion = "0.101")
        }
        val warnings = stderr.lines().filter { it.contains("versions differ") }
        assertEquals(1, warnings.size, stderr)
        assertTrue(warnings.single().startsWith("WARN:"), warnings.single())
        assertTrue(warnings.single().contains("devrig 0.101"), warnings.single())
        assertTrue(warnings.single().contains("MCP Steroid 0.100"), warnings.single())
        assertTrue(warnings.single().contains("pid $pid"), warnings.single())
    }

    @Test
    fun `does not warn when versions match or plugin version is blank`() {
        val stderr = captureStderr {
            BackendVersionSkew.warnIfSkewed(pid = 918_273_002L, pluginVersion = "0.101", devrigVersion = "0.101")
            BackendVersionSkew.warnIfSkewed(pid = 918_273_003L, pluginVersion = "", devrigVersion = "0.101")
        }
        assertEquals(emptyList<String>(), stderr.lines().filter { it.contains("versions differ") })
    }
}
