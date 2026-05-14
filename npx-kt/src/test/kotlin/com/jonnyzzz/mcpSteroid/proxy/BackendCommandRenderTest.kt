/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private fun render(rows: List<BackendRow>): String {
        val buf = ByteArrayOutputStream()
        renderBackendOutput(rows, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8)
    }

    private fun markerIde(
        name: String,
        version: String,
        pid: Long,
        build: String = "$name-$version-build",
        mcpUrl: String = "http://127.0.0.1:65000/mcp",
    ): DiscoveredIde {
        val ideInfo = IdeInfo(name = name, version = version, build = build)
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

    private fun portIde(
        port: Int = 63342,
        productFullName: String? = "IntelliJ IDEA Ultimate",
        productName: String? = "IDEA",
        buildNumber: String? = "IU-253.21581.142",
        baselineVersion: Int? = 253,
        edition: String? = "IU",
    ) = DiscoveredIdeByPort(
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = productName,
        productFullName = productFullName,
        edition = edition,
        baselineVersion = baselineVersion,
        buildNumber = buildNumber,
    )

    // -------------------------- empty / no-IDE branch ----------------------

    @Test
    fun `empty row list prints the no-IDEs message`() {
        val text = render(emptyList())
        assertEquals("No IDEs detected.\n", text)
    }

    // --------------------- marker rows: happy paths ------------------------

    @Test
    fun `single marker IDE with one project prints version, pid, and name to path mapping`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2025.3.3", pid = 1234L),
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
    fun `marker IDE with multiple projects lists every name and path`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("PyCharm", "2025.3.1", pid = 4242L),
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
            BackendRow.FromMarker(markerIde("IntelliJ IDEA", "2025.3.3", 1L), listOf(ProjectInfo("a", "/a"))),
            BackendRow.FromMarker(markerIde("PyCharm", "2025.3.1", 2L), listOf(ProjectInfo("b", "/b"))),
        )
        val text = render(rows)
        val sections = text.trimEnd().split("\n\n")
        assertEquals(2, sections.size,
            "expected exactly one blank-line separator between IDE blocks, got: $text")
    }

    // --------------------- marker rows: edge cases -------------------------

    @Test
    fun `marker IDE with empty projects list prints 'no open projects'`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("GoLand", "2025.3.0", pid = 99L),
                projects = emptyList(),
            )
        )
        val text = render(rows)
        assertTrue(text.contains("GoLand"), text)
        assertTrue(text.contains("(no open projects)"),
            "should signal empty-list case explicitly: $text")
    }

    @Test
    fun `unreachable marker IDE prints the error reason on its own line`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("WebStorm", "2025.3.0", pid = 7L),
                projects = null,
                errorMessage = "timed out after 8.seconds",
            )
        )
        val text = render(rows)
        assertTrue(text.contains("(unreachable: timed out after 8.seconds)"),
            "should surface the error: $text")
    }

    @Test
    fun `unreachable marker IDE with null errorMessage still prints a coherent fallback`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("Rider", "2025.3.0", pid = 8L),
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
        val rows = listOf(BackendRow.FromMarker(markerIde("IntelliJ IDEA", "1", 1L), emptyList()))
        assertTrue(render(rows).endsWith("\n"), "must end with newline; got: ${render(rows)}")
    }

    @Test
    fun `marker IDE name and version are NOT mangled even when they contain spaces`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA Ultimate", "2025.3.3 EAP", 1L),
                projects = listOf(ProjectInfo("p", "/p")),
            )
        )
        val text = render(rows)
        assertTrue(text.contains("IntelliJ IDEA Ultimate  version 2025.3.3 EAP"),
            "got: $text")
    }

    // ---------------------- port rows (NEW coverage) -----------------------

    @Test
    fun `port-discovered IDE shows productFullName + buildNumber + port locator`() {
        // This is the case the user hit: an IDE running with the IntelliJ
        // built-in HTTP server reachable, but no `.mcp-steroid` marker.
        val rows = listOf(BackendRow.FromPort(portIde(port = 63342)))
        val text = render(rows)
        assertTrue(text.contains("IntelliJ IDEA Ultimate  version IU-253.21581.142  (port 63342)"),
            "expected the full IDE header line; got:\n$text")
        assertTrue(text.contains("mcp-steroid plugin not installed"),
            "must explain why projects are unavailable; got:\n$text")
    }

    @Test
    fun `port-discovered IDE falls back to productName when productFullName is null`() {
        val rows = listOf(BackendRow.FromPort(portIde(productFullName = null, productName = "IDEA")))
        val text = render(rows)
        assertTrue(text.contains("IDEA  version "), "expected fallback to productName; got:\n$text")
    }

    @Test
    fun `port-discovered IDE falls back to baselineVersion when buildNumber is null`() {
        val rows = listOf(BackendRow.FromPort(portIde(buildNumber = null, baselineVersion = 253)))
        val text = render(rows)
        assertTrue(text.contains("version 253"), "expected baseline fallback; got:\n$text")
    }

    @Test
    fun `port-discovered IDE with no productFullName, no productName -- still renders a header`() {
        // A JSON 200 from some other service that looks vaguely like /api/about
        // should still produce a coherent line (the discovery layer would have
        // filtered it out, but the renderer is defensive).
        val rows = listOf(BackendRow.FromPort(portIde(productFullName = null, productName = null)))
        val text = render(rows)
        assertTrue(text.contains("(unknown JetBrains IDE)"),
            "expected a defensive fallback name; got:\n$text")
    }

    // --------------- mixed list (marker + port) --------------------------

    @Test
    fun `mixed list renders marker rows first, then port rows`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde("PyCharm", "2025.3.1", 1L), listOf(ProjectInfo("p", "/p"))),
            BackendRow.FromPort(portIde(port = 63342, productFullName = "GoLand")),
        )
        val text = render(rows)
        val pycharmIndex = text.indexOf("PyCharm")
        val golandIndex = text.indexOf("GoLand")
        assertTrue(pycharmIndex < golandIndex,
            "marker row (PyCharm) should appear before port row (GoLand); got:\n$text")
    }

    @Test
    fun `mixed list separates marker and port rows with the same blank-line rule`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde("PyCharm", "2025.3.1", 1L), emptyList()),
            BackendRow.FromPort(portIde(port = 63342)),
        )
        val text = render(rows)
        val sections = text.trimEnd().split("\n\n")
        assertEquals(2, sections.size,
            "expected one blank-line separator between marker and port rows; got:\n$text")
    }

    // ----------------------- deduplication (mergeRows) ---------------------

    @Test
    fun `mergeRows drops a port IDE whose build matches a marker row`() {
        // The same running IDE shows up in BOTH discoveries when it has the
        // mcp-steroid plugin AND its built-in HTTP server is on a scanned port.
        // The marker row carries the project list; the port row is redundant.
        val markerRows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2025.3.3", 1L, build = "IU-253.21581.142"),
                projects = emptyList(),
            )
        )
        val portIdes = setOf(portIde(port = 63342, buildNumber = "IU-253.21581.142"))

        val merged = mergeRows(markerRows, portIdes)
        assertEquals(1, merged.size, "duplicate IDE should collapse to one row; got: $merged")
        assertTrue(merged.single() is BackendRow.FromMarker,
            "should keep the marker row (it has projects); got: ${merged.single()}")
    }

    @Test
    fun `mergeRows keeps a port IDE whose build does NOT match any marker`() {
        val markerRows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2025.3.3", 1L, build = "IU-253.21581.142"),
                projects = emptyList(),
            )
        )
        val portIdes = setOf(portIde(port = 63343, buildNumber = "GO-253.99.0"))

        val merged = mergeRows(markerRows, portIdes)
        assertEquals(2, merged.size, "different-build IDE should remain; got: $merged")
    }

    @Test
    fun `mergeRows keeps a port IDE with null buildNumber even when markers are present`() {
        // We can't decide "same IDE" without a build identifier. Better to
        // show the user one extra row than to hide a real running IDE.
        val markerRows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2025.3.3", 1L, build = "IU-253.21581.142"),
                projects = emptyList(),
            )
        )
        val portIdes = setOf(portIde(port = 63344, buildNumber = null))

        val merged = mergeRows(markerRows, portIdes)
        assertEquals(2, merged.size, "null-build port row should NOT be deduplicated away; got: $merged")
    }

    @Test
    fun `mergeRows preserves marker order and sorts port rows by port`() {
        val markerRows = listOf(
            BackendRow.FromMarker(markerIde("PyCharm", "2025.3.1", 1L), emptyList()),
            BackendRow.FromMarker(markerIde("IntelliJ IDEA", "2025.3.3", 2L), emptyList()),
        )
        val portIdes = setOf(
            portIde(port = 63350, buildNumber = "X-1"),
            portIde(port = 63345, buildNumber = "Y-2"),
        )

        val merged = mergeRows(markerRows, portIdes)
        val ports = merged.filterIsInstance<BackendRow.FromPort>().map { it.ide.port }
        assertEquals(listOf(63345, 63350), ports, "port rows must sort ascending; got: $merged")
        val markerIndex = merged.indexOfFirst { it is BackendRow.FromMarker }
        val firstPortIndex = merged.indexOfFirst { it is BackendRow.FromPort }
        assertTrue(markerIndex < firstPortIndex, "markers must come before ports; got: $merged")
    }

    @Test
    fun `mergeRows on empty inputs returns empty list`() {
        assertEquals(emptyList<BackendRow>(), mergeRows(emptyList(), emptySet()))
    }

    @Test
    fun `mergeRows tolerates marker rows with null build`() {
        // PidMarker.ide.build is non-nullable on the wire, but be defensive:
        // dedup must not throw when comparing nulls.
        val markerRows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2025.3.3", 1L, build = ""),
                projects = emptyList(),
            )
        )
        val portIdes = setOf(portIde(port = 63345))

        val merged = mergeRows(markerRows, portIdes)
        assertFalse(merged.isEmpty(), "should not collapse to empty; got: $merged")
    }
}
