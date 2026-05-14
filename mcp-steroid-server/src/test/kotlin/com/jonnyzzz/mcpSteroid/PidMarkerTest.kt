package com.jonnyzzz.mcpSteroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PidMarkerTest {

    private val sample = PidMarker(
        pid = 12345,
        mcpUrl = "http://localhost:64531/mcp",
        port = 64531,
        token = "deadbeefcafebabe",
        ide = IdeInfo(name = "IntelliJ IDEA", version = "2025.3.3", build = "IU-253.1.1"),
        plugin = PluginInfo(id = "com.jonnyzzz.mcpSteroid", name = "MCP Steroid", version = "1.0.0"),
        createdAt = "2026-05-10T12:34:56Z",
    )

    @Test
    fun `roundtrip preserves all fields`() {
        val text = PidMarkerJson.encode(sample)
        val decoded = PidMarkerJson.decode(text)
        assertEquals(sample, decoded)
    }

    @Test
    fun `encoded form is pretty-printed JSON`() {
        val text = PidMarkerJson.encode(sample)
        assertTrue(text.startsWith("{\n"), "expected pretty-printed start, got: $text")
        assertTrue(text.contains("\"schema\": 1"), "schema field missing: $text")
        assertTrue(text.contains("\"pid\": 12345"), "pid field missing: $text")
        assertTrue(text.contains("\"mcpUrl\": \"http://localhost:64531/mcp\""), "mcpUrl field missing: $text")
        assertTrue(text.contains("\"port\": 64531"), "port field missing: $text")
        assertTrue(text.contains("\"token\": \"deadbeefcafebabe\""), "token field missing: $text")
    }

    @Test
    fun `intellijMcpServer roundtrips when present`() {
        val withIde = sample.copy(
            intellijMcpServer = IntelliJMcpServerInfo(
                enabled = true,
                port = 64342,
                streamUrl = "http://127.0.0.1:64342/stream",
                sseUrl = "http://127.0.0.1:64342/sse",
            )
        )
        val text = PidMarkerJson.encode(withIde)
        val decoded = PidMarkerJson.decode(text)
        assertEquals(withIde, decoded)
        assertTrue(text.contains("\"intellijMcpServer\""), "intellijMcpServer field missing: $text")
        assertTrue(text.contains("\"streamUrl\": \"http://127.0.0.1:64342/stream\""), "streamUrl missing")
    }

    @Test
    fun `decode tolerates absent intellijMcpServer field (defaults to null)`() {
        val text = PidMarkerJson.encode(sample.copy(intellijMcpServer = null))
        val decoded = PidMarkerJson.decode(text)
        assertEquals(null, decoded.intellijMcpServer)
    }

    @Test
    fun `decode of legacy marker without port + token falls back to defaults`() {
        val legacy = """
            {
              "schema": 1,
              "pid": 12345,
              "mcpUrl": "http://localhost:64531/mcp",
              "ide": {"name":"IntelliJ IDEA","version":"x","build":"y"},
              "plugin": {"id":"x","name":"y","version":"z"},
              "createdAt": "2026-05-10T12:34:56Z"
            }
        """.trimIndent()
        val decoded = PidMarkerJson.decode(legacy)
        assertEquals(0, decoded.port)
        assertEquals("", decoded.token)
        assertEquals(12345L, decoded.pid)
    }

    @Test
    fun `decode tolerates unknown future fields (forward-compat)`() {
        val futureJson = """
            {
              "schema": 7,
              "pid": 12345,
              "mcpUrl": "http://localhost:64531/mcp",
              "ide": {"name":"IntelliJ IDEA","version":"2025.3.3","build":"IU-253.1.1"},
              "plugin": {"id":"x","name":"y","version":"z"},
              "createdAt": "2026-05-10T12:34:56Z",
              "futureField": {"nested": [1,2,3]},
              "anotherFuture": "ignored"
            }
        """.trimIndent()
        val decoded = PidMarkerJson.decode(futureJson)
        assertEquals(7, decoded.schema)
        assertEquals(12345L, decoded.pid)
    }

    @Test
    fun `decode rejects required-field omission`() {
        val badJson = """
            {
              "pid": 12345,
              "mcpUrl": "http://localhost:64531/mcp"
            }
        """.trimIndent()
        assertThrows(Exception::class.java) { PidMarkerJson.decode(badJson) }
    }

    @Test
    fun `file name contract`() {
        assertEquals(".12345.mcp-steroid", PidMarker.fileNameFor(12345))
        assertEquals(12345L, PidMarker.pidFromFileName(".12345.mcp-steroid"))
        assertEquals(null, PidMarker.pidFromFileName(".mcp-steroid"))
        assertEquals(null, PidMarker.pidFromFileName("12345.mcp-steroid"))
        assertEquals(null, PidMarker.pidFromFileName(".12345.mcp-steroid.json"))
    }
}
