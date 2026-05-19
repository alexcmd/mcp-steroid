package com.jonnyzzz.mcpSteroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class PidMarkerLayoutTest {

    @Test
    fun `markerDirectory returns managed markers directory under user home`() {
        val userHome = Path.of("/tmp/example-home")

        assertEquals(
            userHome.resolve(".mcp-steroid").resolve("markers"),
            PidMarker.markerDirectory(userHome, env = emptyMap()),
        )
    }

    @Test
    fun `markerFileNameFor returns pid file without leading dot`() {
        assertEquals("1234.mcp-steroid", PidMarker.markerFileNameFor(1234))
    }

    @Test
    fun `MCP_STEROID_HOME overrides managed marker home`() {
        val userHome = Path.of("/tmp/example-home")
        val override = Path.of("/tmp/custom-mcp-home")

        assertEquals(
            override.resolve("markers"),
            PidMarker.markerDirectory(userHome, env = mapOf(PidMarker.MCP_STEROID_HOME_ENV to override.toString())),
        )
    }
}
