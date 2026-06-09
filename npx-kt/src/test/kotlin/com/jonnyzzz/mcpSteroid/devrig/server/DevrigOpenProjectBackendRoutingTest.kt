/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorStatus
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DevrigOpenProjectBackendRoutingTest {
    @Test
    fun `resolveBackend matches the pid- stable id`() {
        val routing = routingWith(pids = listOf(42L, 43L))
        assertEquals(42L, routing.resolveBackend("pid-42")?.pid)
        assertEquals(43L, routing.resolveBackend("pid-43")?.pid)
    }

    @Test
    fun `resolveBackend trims surrounding whitespace`() {
        val routing = routingWith(pids = listOf(42L))
        assertEquals(42L, routing.resolveBackend("  pid-42  ")?.pid)
    }

    @Test
    fun `resolveBackend returns null for unknown name`() {
        val routing = routingWith(pids = listOf(42L))
        assertNull(routing.resolveBackend("pid-999"))
        assertNull(routing.resolveBackend("garbage"))
        assertNull(routing.resolveBackend(""))
    }

    @Test
    fun `backendNameForIde returns the pid- form`() {
        val routing = routingWith(pids = listOf(7L))
        val ide = routing.newestIdeOrNull()!!
        assertEquals("pid-7", routing.backendNameForIde(ide))
    }

    @Test
    fun `discoveredBackends pairs every pid- id with its ide`() {
        val routing = routingWith(pids = listOf(42L, 43L))
        val backends = routing.discoveredBackends()
        assertEquals(setOf("pid-42", "pid-43"), backends.map { it.first }.toSet())
        assertEquals(setOf(42L, 43L), backends.map { it.second.pid }.toSet())
        for ((name, ide) in backends) {
            assertEquals(name, routing.backendNameForIde(ide))
        }
    }

    private fun routingWith(pids: List<Long>): DevrigProjectRoutingService {
        val states = pids.map { pid ->
            IdeMonitorState(
                ide = discoveredIde(pid),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = emptyList(),
            )
        }
        return DevrigProjectRoutingService { states.associateBy { it.ide.pid } }
    }

    private fun discoveredIde(pid: Long, build: String = "IU-261.1"): DiscoveredIde =
        DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:4343/mcp").rpcBaseUrl,
            bridgeHeaders = mapOf("Authorization" to "Bearer secret-$pid"),
            markerPath = "/tmp/$pid.mcp-steroid",
            marker = PidMarker(
                schema = PidMarker.SCHEMA_VERSION,
                pid = pid,
                mcpSteroidServer = McpSteroidServerInfo(
                    mcpUrl = "http://127.0.0.1:4343/mcp",
                    headers = mapOf("Authorization" to "Bearer secret-$pid"),
                ),
                devrigEndpoint = testDevrigEndpoint("http://127.0.0.1:4343/mcp", mapOf("Authorization" to "Bearer secret-$pid")),
                ide = IdeInfo("IntelliJ IDEA", "2026.1", build),
                plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
                createdAt = "2026-05-17T00:00:00Z",
                intellijWebServer = null,
                intellijMcpServer = null,
            ),
        )
}
