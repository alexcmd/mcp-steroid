/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the devrig<->plugin protocol forward/backward compatibility for the ONLY wire-crossing change
 * introduced by the backend_name feature: [ProjectInfo.backend], carried over `/projects/stream`.
 *
 * Everything else (OpenProjectParams.backendName, ListProjectsResponse.backends, BackendSummary) is
 * MCP-surface only and never crosses the devrig<->IDE wire. backend_name is resolved inside devrig and
 * NEVER forwarded to the IDE bridge — the authoritative non-forward assertion (forwarded
 * arguments["backend_name"] == null) lives in npx-kt's DevrigToolBridgeClientTest.
 *
 * Compatibility relies on McpJson having ignoreUnknownKeys=true + explicitNulls=false
 * (mcp-core/.../McpJson.kt): an old peer ignores the unknown `backend` key, a new peer omits a null one.
 */
class WireCompatBackendFieldTest {
    // OLD plugin -> NEW devrig: a /projects/stream payload from an older plugin omits `backend`.
    @Test
    fun `old plugin ProjectInfo without backend decodes on new devrig`() {
        val old = """{"name":"proj","path":"/p"}"""
        val decoded = McpJson.decodeFromString(ProjectInfo.serializer(), old)
        assertEquals("proj", decoded.name)
        assertEquals("/p", decoded.path)
        assertNull(decoded.backend)
    }

    // NEW plugin -> OLD devrig: a newer payload carrying `backend` must be tolerated. A present backend
    // IS emitted (explicitNulls=false only drops nulls); an older peer with no `backend` field would
    // ignore the unknown key (ignoreUnknownKeys=true) rather than throw. Decoding the richer payload
    // back into the SAME serializer is lossless.
    @Test
    fun `new ProjectInfo with backend is emitted and tolerated by tolerant decode`() {
        val newPayload = McpJson.encodeToString(
            ProjectInfo.serializer(),
            ProjectInfo(name = "proj", path = "/p", backend = "pid-1234"),
        )
        assertTrue(newPayload.contains("pid-1234"), "present backend must be emitted on the wire")

        val roundTrip = McpJson.decodeFromString(ProjectInfo.serializer(), newPayload)
        assertEquals("pid-1234", roundTrip.backend)

        // Simulate an OLD decoder that does not know `backend`: ignoreUnknownKeys=true tolerates it.
        val asUnknownKey = McpJson.decodeFromString(LegacyProjectInfo.serializer(), newPayload)
        assertEquals("proj", asUnknownKey.name)
        assertEquals("/p", asUnknownKey.path)
    }

    // A null backend is omitted from the wire entirely (explicitNulls=false), so a new sender never
    // pushes a `backend` key an old receiver would have to reason about.
    @Test
    fun `null backend is omitted from the wire`() {
        val payload = McpJson.encodeToString(
            ProjectInfo.serializer(),
            ProjectInfo(name = "proj", path = "/p", backend = null),
        )
        assertFalse(payload.contains("backend"), "null backend must not appear on the wire")
    }

    /** Models the pre-feature ProjectInfo shape (no `backend` field) to exercise an old decoder. */
    @kotlinx.serialization.Serializable
    private data class LegacyProjectInfo(val name: String, val path: String)
}
