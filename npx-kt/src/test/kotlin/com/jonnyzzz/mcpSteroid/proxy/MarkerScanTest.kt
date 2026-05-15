/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MarkerScanTest {

    private val ourPid = ProcessHandle.current().pid()

    private fun sampleMarker(pid: Long, url: String, ideName: String = "IntelliJ IDEA") = PidMarker(
        pid = pid,
        mcpUrl = url,
        ide = IdeInfo(name = ideName, version = "2025.3.3", build = "IU-253.1.1"),
        plugin = PluginInfo(id = "com.jonnyzzz.mcpSteroid", name = "MCP Steroid", version = "1.0.0"),
        createdAt = "2026-05-10T12:34:56Z",
    )

    @Test
    fun `scanMarkers picks up a JSON marker for the live current pid`(@TempDir homeDir: Path) {
        val marker = sampleMarker(ourPid, "http://localhost:64531/mcp")
        writeManagedMarker(homeDir, marker)

        val entries = scanMarkers(homeDir.toFile(), allowHosts = listOf("localhost"), env = emptyMap())

        assertEquals(1, entries.size, "expected the marker for our own pid to be discovered")
        val entry = entries.single()
        assertEquals(ourPid, entry.pid)
        assertEquals("http://localhost:64531/mcp", entry.url)
        assertEquals("IntelliJ IDEA", entry.label)
    }

    @Test
    fun `scanMarkers picks up a legacy JSON marker for the transition window`(@TempDir homeDir: Path) {
        val marker = sampleMarker(ourPid, "http://localhost:64532/mcp")
        writeLegacyMarker(homeDir, marker)

        val entries = scanMarkers(homeDir.toFile(), allowHosts = listOf("localhost"), env = emptyMap())

        assertEquals(listOf(ourPid), entries.map { it.pid })
        assertEquals("http://localhost:64532/mcp", entries.single().url)
    }

    @Test
    fun `scanMarkers prefers managed marker when both layouts exist for the same pid`(@TempDir homeDir: Path) {
        writeLegacyMarker(homeDir, sampleMarker(ourPid, "http://localhost:64532/mcp"))
        writeManagedMarker(homeDir, sampleMarker(ourPid, "http://localhost:64533/mcp"))

        val entries = scanMarkers(homeDir.toFile(), allowHosts = listOf("localhost"), env = emptyMap())

        assertEquals(1, entries.size)
        assertEquals("http://localhost:64533/mcp", entries.single().url)
    }

    @Test
    fun `scanMarkers ignores markers for dead pids`(@TempDir homeDir: Path) {
        // To get a guaranteed-dead pid we use a pid we just spawned + waited on.
        val process = ProcessBuilder("/bin/echo", "marker-test").start()
        process.waitFor()
        val gonePid = process.pid()
        val marker = sampleMarker(gonePid, "http://localhost:64531/mcp")
        writeManagedMarker(homeDir, marker)

        val entries = scanMarkers(homeDir.toFile(), allowHosts = listOf("localhost"), env = emptyMap())
        assertEquals(emptyList(), entries.map { it.pid })
    }

    @Test
    fun `scanMarkers ignores dead pids in legacy location`(@TempDir homeDir: Path) {
        val process = ProcessBuilder("/bin/echo", "marker-test").start()
        process.waitFor()
        val gonePid = process.pid()
        writeLegacyMarker(homeDir, sampleMarker(gonePid, "http://localhost:64531/mcp"))

        val entries = scanMarkers(homeDir.toFile(), allowHosts = listOf("localhost"), env = emptyMap())

        assertEquals(emptyList(), entries.map { it.pid })
    }

    @Test
    fun `scanMarkers tolerates unknown future fields in the marker JSON`(@TempDir homeDir: Path) {
        val futureJson = """
            {
              "schema": 99,
              "pid": $ourPid,
              "mcpUrl": "http://localhost:64531/mcp",
              "ide": {"name":"IntelliJ IDEA","version":"x","build":"y"},
              "plugin": {"id":"x","name":"y","version":"z"},
              "createdAt": "2026-05-10T12:34:56Z",
              "futureField": {"nested": [1,2,3]}
            }
        """.trimIndent()
        writeManagedMarkerText(homeDir, ourPid, futureJson)

        val entries = scanMarkers(homeDir.toFile(), allowHosts = listOf("localhost"), env = emptyMap())
        val entry = entries.single()
        assertEquals(ourPid, entry.pid)
    }

    @Test
    fun `scanMarkers skips markers whose mcpUrl host is not in the allowlist`(@TempDir homeDir: Path) {
        val marker = sampleMarker(ourPid, "http://malicious.example.com:8080/mcp")
        writeManagedMarker(homeDir, marker)

        val entries = scanMarkers(homeDir.toFile(), allowHosts = listOf("localhost", "127.0.0.1"), env = emptyMap())
        assertEquals(emptyList(), entries.map { it.pid })
    }

    @Test
    fun `scanMarkers tolerates corrupt marker files without crashing`(@TempDir homeDir: Path) {
        writeManagedMarkerText(homeDir, ourPid, "not even close to JSON {{{")

        val entries = scanMarkers(homeDir.toFile(), allowHosts = listOf("localhost"), env = emptyMap())
        assertEquals(emptyList(), entries)
    }

    @Test
    fun `parseMarkerContent returns null on legacy text-format marker (legacy is no longer supported)`() {
        val legacy = """
            http://localhost:64531/mcp

            MCP Steroid Server
            URL: http://localhost:64531/mcp
        """.trimIndent()
        assertNull(parseMarkerContent(legacy, ourPid))
    }

    @Test
    fun `parseMarkerContent extracts url + label from the JSON marker`() {
        val marker = sampleMarker(ourPid, "http://127.0.0.1:8765/mcp", ideName = "GoLand")
        val parsed = parseMarkerContent(PidMarkerJson.encode(marker), ourPid)
        assertNotNull(parsed)
        assertEquals("http://127.0.0.1:8765/mcp", parsed.url)
        assertEquals("GoLand", parsed.label)
    }

    private fun writeManagedMarker(homeDir: Path, marker: PidMarker): File =
        writeManagedMarkerText(homeDir, marker.pid, PidMarkerJson.encode(marker))

    private fun writeManagedMarkerText(homeDir: Path, pid: Long, text: String): File {
        val markerDir = PidMarker.markerDirectory(homeDir, env = emptyMap())
        Files.createDirectories(markerDir)
        return markerDir.resolve(PidMarker.markerFileNameFor(pid)).toFile().also { it.writeText(text) }
    }

    private fun writeLegacyMarker(homeDir: Path, marker: PidMarker): File =
        homeDir.resolve(".${marker.pid}.mcp-steroid").toFile().also {
            it.writeText(PidMarkerJson.encode(marker))
        }
}
