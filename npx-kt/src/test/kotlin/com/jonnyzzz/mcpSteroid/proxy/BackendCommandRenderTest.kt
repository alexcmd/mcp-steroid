/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Pure-text test for the `backend` subcommand's renderer. Decoupled from
 * `runBackendCommand` so every output branch is exercisable without HTTP /
 * coroutines / marker files on disk.
 *
 * The format is part of the CLI's contract — scripts can grep this output.
 * Pin every visible string here so refactors that silently change wording
 * fail the build.
 */
class BackendCommandRenderTest {

    private fun render(rows: List<BackendIdeRow>): String {
        val buf = ByteArrayOutputStream()
        renderBackendOutput(rows, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8)
    }

    private fun ide(name: String, version: String, pid: Long, mcpUrl: String = "http://127.0.0.1:65000/mcp"): DiscoveredIde {
        val ideInfo = IdeInfo(name = name, version = version, build = "$name-$version-build")
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test")
        val marker = PidMarker(
            pid = pid,
            mcpUrl = mcpUrl,
            port = 65000,
            token = "",
            ide = ideInfo,
            plugin = pluginInfo,
            createdAt = "1970-01-01T00:00:00Z",
        )
        return DiscoveredIde(pid = pid, mcpUrl = mcpUrl, markerPath = "/tmp/.$pid.mcp-steroid", marker = marker)
    }

    // -------------------------- empty / no-IDE branch ----------------------

    @Test
    fun `empty row list prints the no-IDEs message`() {
        val text = render(emptyList())
        assertEquals("No IDEs detected.\n", text)
    }

    // ------------------------------- happy paths ---------------------------

    @Test
    fun `single IDE with one project prints version, pid, and name to path mapping`() {
        val rows = listOf(
            BackendIdeRow(
                ide = ide("IntelliJ IDEA", "2025.3.3", pid = 1234L),
                projects = listOf(ProjectInfo(name = "my-app", path = "/Users/x/Work/my-app")),
            )
        )
        val text = render(rows)
        assertTrue(text.contains("IntelliJ IDEA"), "ide name missing: $text")
        assertTrue(text.contains("version 2025.3.3"), "version missing: $text")
        assertTrue(text.contains("pid 1234"), "pid missing: $text")
        assertTrue(text.contains("  my-app -> /Users/x/Work/my-app"), "project line missing: $text")
    }

    @Test
    fun `single IDE with multiple projects lists every name and path`() {
        val rows = listOf(
            BackendIdeRow(
                ide = ide("PyCharm", "2025.3.1", pid = 4242L),
                projects = listOf(
                    ProjectInfo(name = "alpha", path = "/p/alpha"),
                    ProjectInfo(name = "beta", path = "/p/beta"),
                ),
            )
        )
        val text = render(rows)
        assertTrue(text.contains("  alpha -> /p/alpha"), "alpha missing: $text")
        assertTrue(text.contains("  beta -> /p/beta"), "beta missing: $text")
    }

    @Test
    fun `multiple IDEs are separated by a blank line so scripts can group them`() {
        val rows = listOf(
            BackendIdeRow(ide("IntelliJ IDEA", "2025.3.3", 1L), listOf(ProjectInfo("a", "/a"))),
            BackendIdeRow(ide("PyCharm", "2025.3.1", 2L), listOf(ProjectInfo("b", "/b"))),
        )
        val text = render(rows)
        // Blank-line separator between IDE blocks. The trailing newline at end of file
        // is not the separator — the separator sits BETWEEN the two header lines.
        val sections = text.trimEnd().split("\n\n")
        assertEquals(2, sections.size,
            "expected exactly one blank-line separator between IDE blocks, got: $text")
    }

    // ------------------------------ edge cases -----------------------------

    @Test
    fun `IDE with empty projects list prints 'no open projects'`() {
        val rows = listOf(
            BackendIdeRow(
                ide = ide("GoLand", "2025.3.0", pid = 99L),
                projects = emptyList(),
            )
        )
        val text = render(rows)
        assertTrue(text.contains("GoLand"), text)
        assertTrue(text.contains("(no open projects)"),
            "should signal empty-list case explicitly: $text")
    }

    @Test
    fun `unreachable IDE prints the error reason on its own line`() {
        val rows = listOf(
            BackendIdeRow(
                ide = ide("WebStorm", "2025.3.0", pid = 7L),
                projects = null,
                errorMessage = "timed out after 8.seconds",
            )
        )
        val text = render(rows)
        assertTrue(text.contains("(unreachable: timed out after 8.seconds)"),
            "should surface the error: $text")
    }

    @Test
    fun `unreachable IDE with null errorMessage still prints a coherent fallback`() {
        val rows = listOf(
            BackendIdeRow(
                ide = ide("Rider", "2025.3.0", pid = 8L),
                projects = null,
                errorMessage = null,
            )
        )
        val text = render(rows)
        assertTrue(text.contains("(unreachable: unreachable)"),
            "should not produce 'null' in user-visible output: $text")
    }

    @Test
    fun `output ends with a newline`() {
        // Convention: a CLI tool's output is terminated so `tool | head -1` has
        // a clean last line. Pin it explicitly.
        val rows = listOf(BackendIdeRow(ide("IntelliJ IDEA", "1", 1L), emptyList()))
        assertTrue(render(rows).endsWith("\n"), "must end with newline; got: ${render(rows)}")
    }

    @Test
    fun `ide name and version are NOT mangled even when they contain spaces`() {
        // `IntelliJ IDEA` already has a space; future products might have more.
        val rows = listOf(
            BackendIdeRow(
                ide = ide("IntelliJ IDEA Ultimate", "2025.3.3 EAP", 1L),
                projects = listOf(ProjectInfo("p", "/p")),
            )
        )
        val text = render(rows)
        assertTrue(text.contains("IntelliJ IDEA Ultimate  version 2025.3.3 EAP"),
            "got: $text")
    }
}
