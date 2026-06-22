/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.server.base36FixedWidth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** R3.3: one uniform `backend_name` scheme `<productCodeLower>-<hash8>` for every source. */
class BackendIdentityTest {
    @Test
    fun `the same pid yields the same id and different pids differ even with the same product`() {
        val a = backendNameForMarker(pid = 1L, build = "IU-261.1")
        val aAgain = backendNameForMarker(pid = 1L, build = "IU-261.1")
        val b = backendNameForMarker(pid = 2L, build = "IU-261.1")
        assertEquals(a, aAgain)
        assertNotEquals(a, b)
        assertTrue(a.startsWith("iu-"))
    }

    @Test
    fun `missing product code falls back to the ide- prefix`() {
        assertTrue(backendNameForMarker(pid = 7L, build = null).startsWith("ide-"))
        assertTrue(backendNameForMarker(pid = 7L, build = "").startsWith("ide-"))
        // A build with no product-code prefix (port /api/about can return "253.x") also falls back.
        assertTrue(backendNameForPort(port = 63342, build = "253.21581.142").startsWith("ide-"))
    }

    @Test
    fun `backendNameForPort is deterministic and keyed by port`() {
        val a = backendNameForPort(port = 65432, build = "IC-253.1")
        val aAgain = backendNameForPort(port = 65432, build = "IC-253.1")
        val b = backendNameForPort(port = 65433, build = "IC-253.1")
        assertEquals(a, aAgain)
        assertNotEquals(a, b)
        assertTrue(a.startsWith("ic-"))
        // Deterministic formula
        assertEquals("ic-" + base36FixedWidth("port:65432", "IC-253.1").take(8), a)
    }

    @Test
    fun `backendNameForManaged is deterministic and keyed by managed id`() {
        val a = backendNameForManaged(managedId = "idea-community-2025.2.6.2", build = "IC-252.1")
        val aAgain = backendNameForManaged(managedId = "idea-community-2025.2.6.2", build = "IC-252.1")
        val b = backendNameForManaged(managedId = "idea-community-2025.3.0", build = "IC-253.1")
        assertEquals(a, aAgain)
        assertNotEquals(a, b)
        assertTrue(a.startsWith("ic-"))
    }
}
