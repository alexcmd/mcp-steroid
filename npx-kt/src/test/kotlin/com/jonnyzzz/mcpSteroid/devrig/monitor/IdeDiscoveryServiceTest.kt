/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeDiscoveryServiceTest {

    private val ourPid = ProcessHandle.current().pid()

    private fun marker(
        pid: Long,
        url: String,
        ideName: String = "IntelliJ IDEA",
    ): PidMarker =
        PidMarker(
            pid = pid,
            mcpUrl = url,
            ide = IdeInfo(name = ideName, version = "x", build = "y"),
            plugin = PluginInfo(id = "x", name = "y", version = "z"),
            createdAt = "2026-05-10T12:34:56Z",
        )

    private fun writeManagedMarker(homeDir: Path, pid: Long, url: String, ideName: String = "IntelliJ IDEA"): java.io.File =
        writeManagedMarker(homeDir, marker(pid, url, ideName))

    private fun writeManagedMarker(homeDir: Path, marker: PidMarker): java.io.File {
        val markerDir = PidMarker.markerDirectory(homeDir, env = emptyMap())
        Files.createDirectories(markerDir)
        val file = markerDir.resolve(PidMarker.markerFileNameFor(marker.pid)).toFile()
        file.writeText(PidMarkerJson.encode(marker))
        return file
    }

    private fun writeLegacyMarker(homeDir: Path, pid: Long, url: String, ideName: String = "IntelliJ IDEA"): java.io.File =
        writeLegacyMarker(homeDir, marker(pid, url, ideName))

    private fun writeLegacyMarker(homeDir: Path, marker: PidMarker): java.io.File {
        val file = homeDir.resolve(".${marker.pid}.mcp-steroid").toFile()
        file.writeText(PidMarkerJson.encode(marker))
        return file
    }

    private fun writeManagedMarkerText(homeDir: Path, pid: Long, text: String): java.io.File {
        val markerDir = PidMarker.markerDirectory(homeDir, env = emptyMap())
        Files.createDirectories(markerDir)
        return markerDir.resolve(PidMarker.markerFileNameFor(pid)).toFile().also { it.writeText(text) }
    }

    private fun writeLegacyMarkerText(homeDir: Path, pid: Long, text: String): java.io.File =
        homeDir.resolve(".$pid.mcp-steroid").toFile().also { it.writeText(text) }

    private fun service(homeDir: Path): IdeDiscoveryService =
        IdeDiscoveryService(
            markersDir = PidMarker.markerDirectory(homeDir, env = emptyMap()).toFile(),
            legacyHomeDir = homeDir.toFile(),
            allowHosts = listOf("localhost"),
        )

    private fun legacyOnlyService(homeDir: Path): IdeDiscoveryService =
        IdeDiscoveryService(
            markersDir = homeDir.resolve("missing-markers").toFile(),
            legacyHomeDir = homeDir.toFile(),
            allowHosts = listOf("localhost"),
        )

    @Test
    fun `scanOnce picks up a marker for the live current pid`(@TempDir homeDir: Path) {
        writeManagedMarker(homeDir, ourPid, "http://localhost:64531/mcp")
        val service = service(homeDir)

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
        writeManagedMarker(homeDir, ourPid, "http://malicious.example:8080/mcp")
        val service = service(homeDir)

        service.scanOnce()

        assertTrue(service.ides.value.isEmpty())
    }

    @Test
    fun `scanOnce skips markers for processes that no longer exist`(@TempDir homeDir: Path) {
        // Spawn + wait → guaranteed-dead pid we can write a marker for.
        val process = ProcessBuilder("/bin/echo", "monitor-test").start()
        process.waitFor()
        writeManagedMarker(homeDir, process.pid(), "http://localhost:1/mcp")

        val service = service(homeDir)
        service.scanOnce()

        assertFalse(service.ides.value.any { it.pid == process.pid() })
    }

    @Test
    fun `scanOnce skips malformed marker files but keeps valid neighbours`(@TempDir homeDir: Path) {
        // Two corrupt markers around one valid one: ensures the scanner doesn't
        // trip on the bad files and silently drop everything else.
        writeManagedMarkerText(homeDir, 101L, "not even close to JSON {{{")
        writeManagedMarker(homeDir, ourPid, "http://localhost:64531/mcp")
        writeManagedMarkerText(homeDir, 102L, "{ \"schema\": 1 }")

        val service = service(homeDir)
        service.scanOnce()

        val ides = service.ides.value
        assertEquals(1, ides.size, "expected only the valid marker, got: $ides")
        assertEquals(ourPid, ides.single().pid)
    }

    @Test
    fun `scanOnce picks up a legacy JSON marker during the transition window`(@TempDir homeDir: Path) {
        writeLegacyMarker(homeDir, ourPid, "http://localhost:64532/mcp")
        val service = legacyOnlyService(homeDir)

        service.scanOnce()

        val ides = service.ides.value
        assertEquals(1, ides.size)
        assertEquals("http://localhost:64532/mcp", ides.single().mcpUrl)
    }

    @Test
    fun `scanOnce prefers managed marker when both layouts exist for the same pid`(@TempDir homeDir: Path) {
        writeLegacyMarker(homeDir, ourPid, "http://localhost:64532/mcp")
        writeManagedMarker(homeDir, ourPid, "http://localhost:64533/mcp")
        val service = service(homeDir)

        service.scanOnce()

        val ides = service.ides.value
        assertEquals(1, ides.size)
        assertEquals("http://localhost:64533/mcp", ides.single().mcpUrl)
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
        writeLegacyMarkerText(
            homeDir,
            ourPid,
            """
            http://localhost:6315/mcp

            MCP Steroid Server
            URL: http://localhost:6315/mcp

            Created: 2026-05-14T18:36:54.186601+02:00
            Plugin Version: 0.95.0-b14969e1
            IDE Build: IU-261.23567.138
            """.trimIndent(),
        )

        val service = legacyOnlyService(homeDir)
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
        writeManagedMarkerText(homeDir, 101L, """{"schema": 1, "pid":""")
        writeManagedMarker(homeDir, ourPid, "http://localhost:64531/mcp")

        val service = service(homeDir)
        service.scanOnce()

        val ides = service.ides.value
        assertEquals(1, ides.size, "expected only the valid marker, got: $ides")
        assertEquals(ourPid, ides.single().pid)
    }
}
