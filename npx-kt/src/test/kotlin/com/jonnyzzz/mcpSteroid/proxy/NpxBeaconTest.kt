/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class NpxBeaconTest {
    @Test
    fun `interactive beacon uses dedicated event and anonymous properties`(@TempDir tempDir: Path) {
        val events = mutableListOf<RecordedEvent>()
        val homePaths = HomePaths(tempDir.resolve(".mcp-steroid"))
        val beacon = NpxBeacon(
            homePaths = homePaths,
            eventSender = { distinctId, event, properties ->
                events += RecordedEvent(distinctId, event, properties)
            },
        )

        beacon.sendEventInternal(
            event = "devrig-interactive",
            properties = mapOf("mode" to "interactive", "invocation" to "backend", "timer" to "startup"),
        )

        val event = events.single()
        assertEquals("devrig-interactive", event.event)
        assertEquals(Files.readString(homePaths.home.resolve("devrig-user-id")).trim(), event.distinctId)
        assertEquals(
            setOf("mode", "invocation", "timer", "tool", "uptime_ms", "proxy_version"),
            event.properties.keys,
        )
        assertEquals("devrig", event.properties["tool"])
        assertEquals("interactive", event.properties["mode"])
        assertEquals("backend", event.properties["invocation"])
        assertEquals("startup", event.properties["timer"])
        assertEquals(ProxyVersionMetadata.getProxyVersion(), event.properties["proxy_version"])
    }

    @Test
    fun `mcp beacon uses dedicated event and stable generated user id`(@TempDir tempDir: Path) {
        val events = mutableListOf<RecordedEvent>()
        val homePaths = HomePaths(tempDir.resolve(".mcp-steroid"))
        val beacon = NpxBeacon(
            homePaths = homePaths,
            eventSender = { distinctId, event, properties ->
                events += RecordedEvent(distinctId, event, properties)
            },
        )

        beacon.sendEventInternal(
            event = "devrig-mcp",
            properties = mapOf("mode" to "mcp", "invocation" to "mcp", "timer" to "startup"),
        )
        beacon.sendEventInternal(
            event = "devrig-mcp",
            properties = mapOf("mode" to "mcp", "invocation" to "mcp", "timer" to "manual"),
        )

        assertEquals(listOf("devrig-mcp", "devrig-mcp"), events.map { it.event })
        assertEquals(events.first().distinctId, events.last().distinctId)
        assertEquals(Files.readString(homePaths.home.resolve("devrig-user-id")).trim(), events.first().distinctId)
        assertEquals("manual", events.last().properties["timer"])
    }

    private data class RecordedEvent(
        val distinctId: String,
        val event: String,
        val properties: Map<String, Any>,
    )
}
