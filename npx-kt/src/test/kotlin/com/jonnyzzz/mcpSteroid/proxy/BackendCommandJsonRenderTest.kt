/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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

/**
 * Pins the schema of `mcp-steroid-proxy backend --json`. This is the
 * machine-readable counterpart of `BackendCommandRenderTest` — same input
 * shapes, different output contract.
 *
 * Why every field matters: scripts will dispatch on `source`, filter on
 * `pluginInstalled`, sum `projects.length`, etc. A schema change that
 * silently breaks any of those would be a wire-level regression.
 */
class BackendCommandJsonRenderTest {

    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun render(rows: List<BackendRow>): JsonObject {
        val buf = ByteArrayOutputStream()
        renderBackendJson(rows, PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8)
        return parser.parseToJsonElement(text).jsonObject
    }

    private fun markerIde(
        name: String = "IntelliJ IDEA",
        version: String = "2025.3.3",
        pid: Long = 1234L,
        build: String = "IU-253.21581.142",
        mcpUrl: String = "http://localhost:6315/mcp",
        token: String = "",
    ): DiscoveredIde {
        val ideInfo = IdeInfo(name = name, version = version, build = build)
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test")
        val marker = PidMarker(
            pid = pid,
            mcpUrl = mcpUrl,
            port = 0,
            token = token,
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

    // ----------------------------- top-level shape -------------------------

    @Test
    fun `output is valid JSON with tool and ides at the top level`() {
        val root = render(emptyList())
        assertNotNull(root["tool"], "tool block must be present")
        assertNotNull(root["ides"], "ides array must be present")
        val tool = root["tool"]!!.jsonObject
        assertEquals("devrig", tool["name"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(tool["version"]?.jsonPrimitive?.contentOrNull,
            "tool.version must be non-null so scripts can correlate against changelog")
    }

    @Test
    fun `empty list produces an empty 'ides' array, not a null`() {
        val root = render(emptyList())
        assertEquals(0, root["ides"]!!.jsonArray.size,
            "empty discovery must serialise as [] so consumers can always iterate")
    }

    // -------------------------- marker variant -----------------------------

    @Test
    fun `marker row serialises with source=marker plus pid + mcpUrl + projects`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde(pid = 1234, mcpUrl = "http://localhost:6315/mcp"),
                projects = listOf(ProjectInfo("my-app", "/Users/x/my-app")),
            )
        )
        val ide = render(rows)["ides"]!!.jsonArray.single().jsonObject

        assertEquals("marker", ide["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IntelliJ IDEA", ide["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("2025.3.3", ide["version"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IU-253.21581.142", ide["build"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1234L, ide["pid"]?.jsonPrimitive?.long)
        assertEquals("http://localhost:6315/mcp", ide["mcpUrl"]?.jsonPrimitive?.contentOrNull)

        val projects = ide["projects"]!!.jsonArray
        assertEquals(1, projects.size)
        val p = projects.single().jsonObject
        assertEquals("my-app", p["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("/Users/x/my-app", p["path"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `marker row with empty projects list serialises projects as an empty array`() {
        val rows = listOf(BackendRow.FromMarker(markerIde(), projects = emptyList()))
        val ide = render(rows)["ides"]!!.jsonArray.single().jsonObject
        val projects = ide["projects"]!!.jsonArray
        assertEquals(0, projects.size)
        assertNull(ide["unreachable"], "no unreachable signal when fetch succeeded with []")
    }

    @Test
    fun `unreachable marker row serialises projects=null AND unreachable=reason`() {
        // jq users will write `select(.projects == null)` to find unreachable
        // IDEs; emit JsonNull (not absent) so that filter works.
        val rows = listOf(
            BackendRow.FromMarker(markerIde(), projects = null, errorMessage = "connect refused")
        )
        val ide = render(rows)["ides"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, ide["projects"], "projects must be explicit JSON null when fetch failed")
        assertEquals("connect refused", ide["unreachable"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `unreachable marker row with null errorMessage gets a non-null fallback string`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde(), projects = null, errorMessage = null)
        )
        val ide = render(rows)["ides"]!!.jsonArray.single().jsonObject
        val unreachable = ide["unreachable"]?.jsonPrimitive?.contentOrNull
        assertNotNull(unreachable, "unreachable must always be a string, never null")
        assertTrue(unreachable!!.isNotBlank(),
            "unreachable must be a non-empty string so jq pipelines don't accidentally get \"\"")
    }

    // --------------------------- port variant ------------------------------

    @Test
    fun `port row serialises with source=port plus port + baseUrl + about-fields + pluginInstalled=false`() {
        val rows = listOf(BackendRow.FromPort(portIde(port = 63342)))
        val ide = render(rows)["ides"]!!.jsonArray.single().jsonObject

        assertEquals("port", ide["source"]?.jsonPrimitive?.contentOrNull)
        // displayName is the productFullName (which carries the marketing version
        // baked in by /api/about). Scripts that want one composite string for
        // display use this; structured consumers slice on the raw fields below.
        assertEquals("IntelliJ IDEA Ultimate", ide["displayName"]?.jsonPrimitive?.contentOrNull)
        assertEquals(63342, ide["port"]?.jsonPrimitive?.int)
        assertEquals("http://127.0.0.1:63342", ide["baseUrl"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IDEA", ide["productName"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IntelliJ IDEA Ultimate", ide["productFullName"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IU", ide["edition"]?.jsonPrimitive?.contentOrNull)
        assertEquals(253, ide["baselineVersion"]?.jsonPrimitive?.int)
        assertEquals("IU-253.21581.142", ide["buildNumber"]?.jsonPrimitive?.contentOrNull)
        // Critical: scripts use `.pluginInstalled == false` to filter
        // "running IDEs we can't introspect projects on".
        assertEquals(false, ide["pluginInstalled"]?.jsonPrimitive?.boolean)
        // Marker-only fields MUST be absent (not null) to keep the shape clean.
        assertNull(ide["pid"], "port row must not carry a pid: $ide")
        assertNull(ide["projects"], "port row must not carry projects: $ide")
        assertNull(ide["mcpUrl"], "port row must not carry mcpUrl: $ide")
    }

    @Test
    fun `port row omits about-fields that came back as null from api-about`() {
        // Older IDEs return only `productName` (no `name`). Don't serialise the
        // nulls — keep the shape compact so scripts using `.productFullName?`
        // get unambiguous "field absent" semantics.
        val rows = listOf(BackendRow.FromPort(portIde(productFullName = null, edition = null, baselineVersion = null)))
        val ide = render(rows)["ides"]!!.jsonArray.single().jsonObject
        assertNull(ide["productFullName"], "absent fields must NOT serialise as null: $ide")
        assertNull(ide["edition"], "absent fields must NOT serialise as null: $ide")
        assertNull(ide["baselineVersion"], "absent fields must NOT serialise as null: $ide")
        // productName was present in the input — keep it.
        assertEquals("IDEA", ide["productName"]?.jsonPrimitive?.contentOrNull)
    }

    // ---------------------------- mixed list -------------------------------

    @Test
    fun `mixed list keeps input order and source discriminator per entry`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde(pid = 1L), emptyList()),
            BackendRow.FromPort(portIde(port = 63342)),
            BackendRow.FromMarker(markerIde(pid = 2L), null, errorMessage = "x"),
        )
        val ides = render(rows)["ides"]!!.jsonArray
        assertEquals(3, ides.size)
        assertEquals("marker", ides[0].jsonObject["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals("port", ides[1].jsonObject["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals("marker", ides[2].jsonObject["source"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `output has no banner -- pure JSON on stdout for safe piping`() {
        // The text renderer prints "devrig vX — tagline" at the top.
        // The JSON renderer MUST NOT — anything before/after the JSON document
        // breaks `jq` consumers (the parser tolerates trailing whitespace but
        // not a banner line).
        val buf = ByteArrayOutputStream()
        renderBackendJson(emptyList(), PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8).trim()
        assertTrue(text.startsWith("{"),
            "output must start with '{' (the JSON document); got first 60 chars: '${text.take(60)}'")
        assertTrue(text.endsWith("}"),
            "output must end with '}' — no trailing banner allowed; got last 60 chars: '${text.takeLast(60)}'")
    }
}
