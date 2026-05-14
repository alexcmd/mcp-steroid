/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.monitor

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeDiscoveryServiceTest {

    private val ourPid = ProcessHandle.current().pid()

    private fun writeMarker(homeDir: Path, pid: Long, url: String, ideName: String = "IntelliJ IDEA"): File {
        val marker = PidMarker(
            pid = pid,
            mcpUrl = url,
            ide = IdeInfo(name = ideName, version = "x", build = "y"),
            plugin = PluginInfo(id = "x", name = "y", version = "z"),
            createdAt = "2026-05-10T12:34:56Z",
        )
        val file = File(homeDir.toFile(), PidMarker.fileNameFor(pid))
        file.writeText(PidMarkerJson.encode(marker))
        return file
    }

    @Test
    fun `scanOnce picks up a marker for the live current pid`(@TempDir homeDir: Path) {
        writeMarker(homeDir, ourPid, "http://localhost:64531/mcp")
        val service = IdeDiscoveryService(homeDir.toFile(), allowHosts = listOf("localhost"))

        service.scanOnce()

        val ides = service.ides.value
        assertEquals(1, ides.size)
        val ide = ides.single()
        assertEquals(ourPid, ide.pid)
        assertEquals("http://localhost:64531/mcp", ide.mcpUrl)
        assertEquals("IntelliJ IDEA pid=$ourPid", ide.label)
    }

    @Test
    fun `scanOnce skips markers with disallowed host`(@TempDir homeDir: Path) {
        writeMarker(homeDir, ourPid, "http://malicious.example:8080/mcp")
        val service = IdeDiscoveryService(homeDir.toFile(), allowHosts = listOf("localhost"))

        service.scanOnce()

        assertTrue(service.ides.value.isEmpty())
    }

    @Test
    fun `scanOnce skips markers for processes that no longer exist`(@TempDir homeDir: Path) {
        // Spawn + wait → guaranteed-dead pid we can write a marker for.
        val process = ProcessBuilder("/bin/echo", "monitor-test").start()
        process.waitFor()
        writeMarker(homeDir, process.pid(), "http://localhost:1/mcp")

        val service = IdeDiscoveryService(homeDir.toFile(), allowHosts = listOf("localhost"))
        service.scanOnce()

        assertFalse(service.ides.value.any { it.pid == process.pid() })
    }

    @Test
    fun `scanOnce skips malformed marker files but keeps valid neighbours`(@TempDir homeDir: Path) {
        // Two corrupt markers around one valid one: ensures the scanner doesn't
        // trip on the bad files and silently drop everything else.
        File(homeDir.toFile(), PidMarker.fileNameFor(101L)).writeText("not even close to JSON {{{")
        writeMarker(homeDir, ourPid, "http://localhost:64531/mcp")
        File(homeDir.toFile(), PidMarker.fileNameFor(102L)).writeText("{ \"schema\": 1 }")

        val service = IdeDiscoveryService(homeDir.toFile(), allowHosts = listOf("localhost"))
        service.scanOnce()

        val ides = service.ides.value
        assertEquals(1, ides.size, "expected only the valid marker, got: $ides")
        assertEquals(ourPid, ides.single().pid)
    }

    @Test
    fun `legacy text-format marker is silently skipped (not exposed, not crashing)`(@TempDir homeDir: Path) {
        // This is exactly the file shape an older plugin build wrote: URL on
        // line 1, blank line, human-readable banner below. It is NOT corrupt —
        // just a historical format we no longer read. The operator should NOT
        // see this surface as a DiscoveredIde.
        //
        // The log-level contract (DEBUG, not WARN) is enforced in production
        // by the `text.trimStart().firstOrNull() != '{'` branch in
        // scanCurrent — a non-JSON marker takes the DEBUG path before the
        // try/catch that WARNs on parse errors. A direct WARN-vs-DEBUG
        // assertion would need logback on the test compile classpath; we
        // instead rely on this behaviour test plus a focused JSON-truncation
        // test below to lock the split.
        File(homeDir.toFile(), PidMarker.fileNameFor(ourPid)).writeText(
            """
            http://localhost:6315/mcp

            MCP Steroid Server
            URL: http://localhost:6315/mcp

            Created: 2026-05-14T18:36:54.186601+02:00
            Plugin Version: 0.95.0-b14969e1
            IDE Build: IU-261.23567.138
            """.trimIndent()
        )

        val service = IdeDiscoveryService(homeDir.toFile(), allowHosts = listOf("localhost"))
        service.scanOnce()
        assertTrue(service.ides.value.isEmpty(),
            "legacy marker must NOT be exposed as DiscoveredIde; got: ${service.ides.value}")
    }

    @Test
    fun `truncated JSON marker is also skipped and does not abort the scan`(@TempDir homeDir: Path) {
        // The other side of the legacy-skip split: a file that LOOKS like JSON
        // (starts with '{') but fails to parse is real corruption. It must
        // still be skipped (not crash the scanner), and valid neighbours must
        // remain visible. Production additionally logs this at WARN to signal
        // genuine corruption to the operator — not asserted here to keep the
        // test classpath logback-free.
        File(homeDir.toFile(), PidMarker.fileNameFor(101L)).writeText("""{"schema": 1, "pid":""")
        writeMarker(homeDir, ourPid, "http://localhost:64531/mcp")

        val service = IdeDiscoveryService(homeDir.toFile(), allowHosts = listOf("localhost"))
        service.scanOnce()

        val ides = service.ides.value
        assertEquals(1, ides.size, "expected only the valid marker, got: $ides")
        assertEquals(ourPid, ides.single().pid)
    }
}
