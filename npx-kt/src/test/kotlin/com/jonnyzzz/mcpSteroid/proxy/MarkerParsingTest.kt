/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarkerParsingTest {
    @Test
    fun `marker parser reads built-in RPC endpoint and token`() {
        val marker = """
            http://127.0.0.1:6315/mcp

            IntelliJ MCP Steroid Server
            URL: http://127.0.0.1:6315/mcp
            Built-in RPC URL: http://127.0.0.1:63342/api/mcp-steroid/v1
            Built-in RPC Token: abc123
            Created: 2026-04-27T00:00:00Z
            Plugin Version: test
            IDE Version: 2026.1
            IntelliJ IDEA 2026.1
        """.trimIndent()

        val parsed = parseMarkerContent(marker, pid = 42)

        assertNotNull(parsed)
        assertEquals("http://127.0.0.1:6315/mcp", parsed.url)
        assertEquals("http://127.0.0.1:63342/api/mcp-steroid/v1", parsed.bridgeBaseUrl)
        assertEquals("abc123", parsed.bridgeToken)
        assertEquals("IntelliJ IDEA 2026.1", parsed.label)
    }

    @Test
    fun `marker scanner preserves allowed built-in RPC endpoint and token`() {
        val home = Files.createTempDirectory("mcp-steroid-marker-test")
        val marker = home.resolve(".${ProcessHandle.current().pid()}.mcp-steroid")
        marker.writeText(
            """
            http://127.0.0.1:6315/mcp

            IntelliJ MCP Steroid Server
            URL: http://127.0.0.1:6315/mcp
            Built-in RPC URL: http://127.0.0.1:63342/api/mcp-steroid/v1
            Built-in RPC Token: abc123
            """.trimIndent(),
        )

        val entries = scanMarkers(home.toFile(), allowHosts = listOf("127.0.0.1", "localhost"))

        val entry = entries.single()
        assertEquals("http://127.0.0.1:6315/mcp", entry.url)
        assertEquals("http://127.0.0.1:63342/api/mcp-steroid/v1", entry.bridgeBaseUrl)
        assertEquals("abc123", entry.bridgeToken)
    }

    @Test
    fun `marker scanner rejects built-in RPC endpoint outside allowed hosts`() {
        val home = Files.createTempDirectory("mcp-steroid-marker-test")
        val marker = home.resolve(".${ProcessHandle.current().pid()}.mcp-steroid")
        marker.writeText(
            """
            http://127.0.0.1:6315/mcp
            Built-in RPC URL: http://192.0.2.10:63342/api/mcp-steroid/v1
            Built-in RPC Token: abc123
            """.trimIndent(),
        )

        val entries = scanMarkers(home.toFile(), allowHosts = listOf("127.0.0.1", "localhost"))

        assertTrue(entries.isEmpty(), "bridge endpoints must obey the same host allow-list as MCP URLs")
    }
}
