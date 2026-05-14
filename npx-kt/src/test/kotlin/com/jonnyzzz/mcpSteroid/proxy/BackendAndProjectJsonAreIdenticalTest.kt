/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Pins the normalized graph emitted by `mcp-steroid-proxy project --json`. */
class ProjectCommandJsonRenderTest {

    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun render(listing: ProjectListing) = ByteArrayOutputStream().let { buf ->
        renderProjectJson(listing, PrintStream(buf, true, Charsets.UTF_8))
        parser.parseToJsonElement(buf.toString(Charsets.UTF_8)).jsonObject
    }

    private fun markerIde(
        name: String = "IntelliJ IDEA",
        version: String = "2025.3.3",
        pid: Long = 1234L,
        build: String = "IU-253.21581.142",
        mcpUrl: String = "http://localhost:6315/mcp",
    ): DiscoveredIde {
        val ideInfo = IdeInfo(name = name, version = version, build = build)
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test")
        val marker = PidMarker(
            pid = pid,
            mcpUrl = mcpUrl,
            port = 0,
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

    @Test
    fun `output is valid JSON with tool ides projects and skipped at the top level`() {
        val root = render(ProjectListing(emptyList(), emptyList()))
        assertNotNull(root["tool"], "tool block must be present")
        assertNotNull(root["ides"], "ides array must be present")
        assertNotNull(root["projects"], "projects array must be present")
        assertNotNull(root["skipped"], "skipped array must be present")
        val tool = root["tool"]!!.jsonObject
        assertEquals("devrig", tool["name"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(tool["version"]?.jsonPrimitive?.contentOrNull,
            "tool.version must be non-null so scripts can correlate against changelog")
        assertEquals(0, root["skipped"]!!.jsonArray.size,
            "skipped must serialise as [] when nothing was skipped")
    }

    @Test
    fun `ide ids are synthetic ide-N keys`() {
        val root = render(
            ProjectListing(
                markerRows = listOf(
                    BackendRow.FromMarker(markerIde(pid = 1L), emptyList()),
                    BackendRow.FromMarker(markerIde(name = "PyCharm", pid = 2L), emptyList()),
                ),
                portRows = emptyList(),
            )
        )
        val ids = root["ides"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.contentOrNull!! }
        assertEquals(listOf("ide-0", "ide-1"), ids)
        assertTrue(ids.all { Regex("^ide-\\d+$").matches(it) },
            "every id must match ide-N; got: $ids")
    }

    @Test
    fun `every project ide reference resolves to an ides entry`() {
        val root = render(
            ProjectListing(
                markerRows = listOf(
                    BackendRow.FromMarker(markerIde(pid = 1L), listOf(ProjectInfo("a", "/a"))),
                    BackendRow.FromMarker(markerIde(name = "PyCharm", pid = 2L), listOf(ProjectInfo("b", "/b"))),
                ),
                portRows = emptyList(),
            )
        )
        val ids = root["ides"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.contentOrNull!! }.toSet()
        val projectRefs = root["projects"]!!.jsonArray.map { it.jsonObject["ide"]!!.jsonPrimitive.contentOrNull!! }
        assertEquals(listOf("ide-0", "ide-1"), projectRefs)
        assertTrue(projectRefs.all { it in ids },
            "every projects[].ide must resolve to ides[].id; ids=$ids refs=$projectRefs")
    }

    @Test
    fun `reachable marker IDE uses backend marker field names inside ides`() {
        val root = render(
            ProjectListing(
                markerRows = listOf(
                    BackendRow.FromMarker(
                        ide = markerIde(pid = 1234L, mcpUrl = "http://localhost:6315/mcp"),
                        projects = listOf(ProjectInfo("my-app", "/Users/x/my-app")),
                    )
                ),
                portRows = emptyList(),
            )
        )
        val ide = root["ides"]!!.jsonArray.single().jsonObject
        assertEquals("IntelliJ IDEA", ide["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("2025.3.3", ide["version"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IU-253.21581.142", ide["build"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1234L, ide["pid"]?.jsonPrimitive?.long)
        assertEquals("http://localhost:6315/mcp", ide["mcpUrl"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `unreachable marker IDE appears in skipped not projects and not ides`() {
        val root = render(
            ProjectListing(
                markerRows = listOf(
                    BackendRow.FromMarker(
                        ide = markerIde(name = "WebStorm", pid = 7L),
                        projects = null,
                        errorMessage = "timed out after 8s",
                    )
                ),
                portRows = emptyList(),
            )
        )
        assertEquals(0, root["ides"]!!.jsonArray.size,
            "unreachable marker IDE must not be in ides because projects cannot reference it")
        assertEquals(0, root["projects"]!!.jsonArray.size,
            "unreachable marker IDE must not produce project rows")
        val skipped = root["skipped"]!!.jsonArray.single().jsonObject
        assertEquals("unreachable: timed out after 8s", skipped["reason"]?.jsonPrimitive?.contentOrNull)
        assertEquals("WebStorm", skipped["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(7L, skipped["pid"]?.jsonPrimitive?.long)
    }

    @Test
    fun `port-discovered IDE appears in skipped with port set and ide absent`() {
        val root = render(
            ProjectListing(
                markerRows = emptyList(),
                portRows = listOf(BackendRow.FromPort(portIde(port = 63342))),
            )
        )
        assertEquals(0, root["ides"]!!.jsonArray.size)
        assertEquals(0, root["projects"]!!.jsonArray.size)
        val skipped = root["skipped"]!!.jsonArray.single().jsonObject
        assertEquals("port-discovered, no mcp-steroid plugin", skipped["reason"]?.jsonPrimitive?.contentOrNull)
        assertEquals(63342, skipped["port"]?.jsonPrimitive?.int)
        assertEquals("IntelliJ IDEA Ultimate", skipped["displayName"]?.jsonPrimitive?.contentOrNull)
        assertNull(skipped["ide"], "port-discovered skipped entries must not carry an ide id: $skipped")
    }
}
