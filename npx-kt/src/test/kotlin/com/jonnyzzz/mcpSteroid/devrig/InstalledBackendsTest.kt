/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
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

    @Test
    fun `installedBackends returns backend with ideHome equal to bundleDir realpath`(@TempDir tempDir: Path) {
        // Build the on-disk layout that installedBackends() expects:
        //   <backendsDir>/<id>/backend.json
        //   <backendsDir>/<id>/<bundleDirName>/
        val id = "idea-community-2025.3.3"
        val bundleDirName = "idea-community"
        val backendDir = tempDir.resolve("backends").resolve(id)
        val bundleDir = backendDir.resolve(bundleDirName)
        Files.createDirectories(bundleDir)
        val launcherPath = "bin/idea.sh"
        val descriptor = BackendDescriptor(
            id = id,
            productKey = "idea-community",
            productCode = "IIC",
            version = "2025.3.3",
            buildNumber = "IC-261.1",
            bundleDirName = bundleDirName,
            launcherPath = launcherPath,
            downloadedAt = "2026-01-01T00:00:00Z",
        )
        writeDescriptor(descriptorPath(backendDir), descriptor)

        val homePaths = HomePaths(tempDir)
        val services = DevrigServices(
            com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost(),
            homePaths = homePaths,
            mcpStdin = System.`in`,
            mcpStdout = System.out,
        )
        val backends = services.installedBackends()

        assertEquals(1, backends.size, "expected one installed backend")
        val backend = backends.single()
        assertEquals(id, backend.id)
        // ideHome must equal normalizeHome(bundleDir) — toRealPath() resolves symlinks (e.g. /var → /private/var on macOS).
        assertEquals(normalizeHome(bundleDir.toString()), backend.ideHome)
    }
}
