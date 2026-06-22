/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class InstalledBackendsTest {

    private fun installed(id: String, home: String): InstalledBackend = InstalledBackend(
        id = id,
        ide = IdeInfo(name = "Test IDE", version = "2026.1", build = "TEST-1"),
        ideHome = home,
        launcher = Path.of(home, "bin", "idea.sh"),
    )

    private fun discoveredIde(ideHome: String?): DiscoveredIde = DiscoveredIde(
        backendName = "test-backend",
        pid = 12345L,
        rpcBaseUrl = "http://localhost:9999",
        bridgeHeaders = emptyMap(),
        ide = IdeInfo(name = "Test IDE", version = "2026.1", build = "TEST-1"),
        plugin = PluginInfo(id = "com.test", name = "Test", version = "1.0"),
        ideHome = ideHome,
    )

    @Test
    fun `startable excludes installed backends already running by ideHome`() {
        val a = installed(id = "idea-community-2026.1", home = "/b/idea")
        val b = installed(id = "goland-2026.1", home = "/b/goland")
        val runningGoland = discoveredIde(ideHome = "/b/goland")
        val startable = startableBackends(listOf(a, b), listOf(runningGoland))
        assertEquals(listOf("idea-community-2026.1"), startable.map { it.id })
    }

    @Test
    fun `startable returns all installed when no running IDEs`() {
        val a = installed(id = "idea-community-2026.1", home = "/b/idea")
        val b = installed(id = "goland-2026.1", home = "/b/goland")
        val startable = startableBackends(listOf(a, b), emptyList())
        assertEquals(listOf("idea-community-2026.1", "goland-2026.1"), startable.map { it.id })
    }

    @Test
    fun `startable ignores running IDEs with null ideHome`() {
        val a = installed(id = "idea-community-2026.1", home = "/b/idea")
        val runningNoHome = discoveredIde(ideHome = null)
        val startable = startableBackends(listOf(a), listOf(runningNoHome))
        assertEquals(listOf("idea-community-2026.1"), startable.map { it.id })
    }

    @Test
    fun `startable returns empty when all installed are running`() {
        val a = installed(id = "idea-community-2026.1", home = "/b/idea")
        val runningIdea = discoveredIde(ideHome = "/b/idea")
        val startable = startableBackends(listOf(a), listOf(runningIdea))
        assertEquals(emptyList<InstalledBackend>(), startable)
    }
}
