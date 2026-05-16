/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NpxBeaconTest {
    @Test
    fun `interactive beacon uses dedicated event and anonymous properties`(@TempDir tempDir: Path) {
        val sink = RecordingBeaconSink()
        val beacon = NpxBeacon(
            proxyVersion = "1.2.3-SNAPSHOT-local",
            userIdFile = tempDir.resolve(".mcp-steroid/devrig-user-id"),
            sink = sink,
        )

        beacon.sendEvent(NpxBeaconMode.INTERACTIVE, invocation = "backend", timer = "startup")

        val event = sink.events.single()
        assertEquals("devrig-interactive", event.event)
        assertEquals(Files.readString(tempDir.resolve(".mcp-steroid/devrig-user-id")).trim(), event.distinctId)
        assertEquals(
            setOf("schema", "tool", "mode", "invocation", "timer", "uptime_ms", "proxy_version"),
            event.properties.keys,
        )
        assertEquals("devrig", event.properties["tool"])
        assertEquals("interactive", event.properties["mode"])
        assertEquals("backend", event.properties["invocation"])
        assertEquals("startup", event.properties["timer"])
        assertEquals("1.2.3-SNAPSHOT-local", event.properties["proxy_version"])
    }

    @Test
    fun `mcp beacon uses dedicated event and stable generated user id`(@TempDir tempDir: Path) {
        val sink = RecordingBeaconSink()
        val userIdFile = tempDir.resolve(".mcp-steroid/devrig-user-id")
        val beacon = NpxBeacon(
            proxyVersion = "1.2.3",
            userIdFile = userIdFile,
            sink = sink,
        )

        beacon.sendEvent(NpxBeaconMode.MCP, invocation = "mcp", timer = "startup")
        beacon.sendEvent(NpxBeaconMode.MCP, invocation = "mcp", timer = "heartbeat")

        assertEquals(listOf("devrig-mcp", "devrig-mcp"), sink.events.map { it.event })
        assertEquals(sink.events.first().distinctId, sink.events.last().distinctId)
        assertEquals(Files.readString(userIdFile).trim(), sink.events.first().distinctId)
        assertEquals("heartbeat", sink.events.last().properties["timer"])
    }

    @Test
    fun `startup beacon delay stays in requested range`() {
        repeat(100) {
            val delayMs = startupBeaconDelay().inWholeMilliseconds
            assertTrue(delayMs in 1_000..5_000, "delay must be 1..5 seconds, got $delayMs")
        }
    }

    private data class RecordedEvent(
        val distinctId: String,
        val event: String,
        val properties: Map<String, Any>,
    )

    private class RecordingBeaconSink : NpxBeaconSink {
        val events = mutableListOf<RecordedEvent>()

        override fun capture(distinctId: String, event: String, properties: Map<String, Any>) {
            events += RecordedEvent(distinctId, event, properties)
        }

        override fun close() = Unit
    }
}
