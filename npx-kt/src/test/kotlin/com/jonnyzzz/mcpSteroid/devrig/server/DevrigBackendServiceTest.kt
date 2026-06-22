/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.InstalledBackend
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DevrigBackendServiceTest {

    @Test
    fun `running candidate is returned as-is without starting`() = runTest {
        val ide = discoveredIde(ideHome = "/b/idea")
        val svc = service(running = listOf(ide), installed = emptyList(), starter = failStarter())
        val out = svc.ensureBackendRunning(OpenProjectCandidate.Running(ide))
        assertSame(ide, out)
    }

    @Test
    fun `startable candidate is started then resolved by ideHome`() = runTest {
        val installed = installed(id = "goland", home = "/b/goland")
        val state = mutableListOf<DiscoveredIde>()
        val svc = service(stateProvider = { state.toList() }, installed = listOf(installed),
            starter = { state += discoveredIde(ideHome = "/b/goland") }) // simulate marker appearing
        val out = svc.ensureBackendRunning(OpenProjectCandidate.Startable(installed))
        assertEquals("/b/goland", out.ideHome)
    }

    @Test
    fun `startable times out with a clear error when no marker appears`() = runTest {
        val installed = installed(id = "goland", home = "/b/goland")
        val svc = service(stateProvider = { emptyList() }, installed = listOf(installed),
            starter = { /* never writes a marker */ })
        val e = assertFailsWith<BackendStartTimeoutException> {
            svc.ensureBackendRunning(OpenProjectCandidate.Startable(installed), timeout = 100.milliseconds)
        }
        assertTrue(e.message!!.contains("did not become reachable"))
    }

    // ---- helpers ----

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

    private fun failStarter(): suspend (InstalledBackend) -> Unit = { throw AssertionError("starter must not be called for running candidate") }

    private fun service(
        running: List<DiscoveredIde> = emptyList(),
        installed: List<InstalledBackend> = emptyList(),
        starter: suspend (InstalledBackend) -> Unit = failStarter(),
        stateProvider: () -> List<DiscoveredIde> = { running },
    ): DevrigBackendService = DevrigBackendService(
        stateProvider = stateProvider,
        installedProvider = { installed },
        starter = starter,
    )
}
