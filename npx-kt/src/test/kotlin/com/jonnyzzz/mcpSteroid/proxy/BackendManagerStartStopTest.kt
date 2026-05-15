/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendManagerStartStopTest {

    @Test
    fun `start writes pid file and stop terminates gracefully`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            ideUserHome = tempDir.resolve("user-home"),
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))

        assertTrue(started.pid > 0)
        assertEquals("${started.pid}\n", Files.readString(homePaths.pidFile("idea-community-2025.3.3")))
        assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
        val marker = tempDir.resolve("user-home").resolve(PidMarker.fileNameFor(started.pid))
        Files.writeString(marker, "marker")

        val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

        assertEquals("stopped", stopped.outcome)
        assertFalse(homePaths.pidFile("idea-community-2025.3.3").exists())
        assertFalse(marker.exists())
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `stop force kills a process that does not exit before the grace period`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = sleepyLauncher())
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            ideUserHome = tempDir.resolve("user-home"),
            stopGracePeriodMillis = 0L,
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))
        assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
        val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

        assertEquals("killed", stopped.outcome)
        assertFalse(homePaths.pidFile("idea-community-2025.3.3").exists())
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `stop treats a missing pid file as successful not running`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val manager = BackendManager(HomePaths(tempDir.resolve("home")), downloader = StaticDownloader)

        val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

        assertEquals("not running", stopped.outcome)
    }

    @Test
    fun `start seeds first-run startup config before launching IDE`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val userHome = tempDir.resolve("user-home")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            ideUserHome = userHome,
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))
        try {
            val configDir = homePaths.cacheDir("idea-community-2025.3.3").resolve("config")
            assertTrue(
                Files.readString(configDir.resolve("options/other.xml"))
                    .contains("experimental.ui.onboarding.proposed.version"),
            )
            assertEquals(
                "switched.from.classic.to.islands\nfalse\n",
                Files.readString(configDir.resolve("early-access-registry.txt")),
            )
            assertTrue(
                Files.readString(configDir.resolve("options/AIOnboardingPromoWindowAdvisor.xml"))
                    .contains("""<option name="wasShown" value="true" />"""),
            )
            assertTrue(
                Files.readString(userHome.resolve(".java/.userPrefs/jetbrains/privacy_policy/prefs.xml"))
                    .contains("""<entry key="euacommunity_accepted_version" value="999.999"/>"""),
            )
            assertTrue(
                Files.readString(userHome.resolve(".config/JetBrains/consentOptions/accepted"))
                    .startsWith("rsch.send.usage.stat:1.1:0:"),
            )
        } finally {
            manager.stop(parseBackendId("idea-community-2025.3.3"))
        }
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    private fun installStubBackend(homePaths: HomePaths, launcherBody: String) {
        val id = "idea-community-2025.3.3"
        val backendDir = homePaths.backendDir(id)
        val bundleDir = backendDir.resolve("idea-IC-253.1")
        val launcher = bundleDir.resolve("bin/idea.sh")
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, launcherBody)
        launcher.toFile().setExecutable(true)
        writeDescriptor(
            descriptorPath(backendDir),
            BackendDescriptor(
                id = id,
                productKey = "idea-community",
                productCode = "IC",
                version = "2025.3.3",
                buildNumber = "IC-253.1",
                bundleDirName = bundleDir.fileName.toString(),
                launcherPath = "bin/idea.sh",
                downloadedAt = "2026-05-14T21:00:00Z",
            ),
        )
    }

    private fun gracefulLauncher(): String =
        """
        #!/usr/bin/env sh
        trap 'exit 0' TERM
        while true; do sleep 1; done
        """.trimIndent() + "\n"

    private fun sleepyLauncher(): String =
        """
        #!/usr/bin/env sh
        sleep 60
        """.trimIndent() + "\n"

    private object StaticDownloader : ManagedBackendDownloader {
        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            BackendDownloadResolution(
                product = IdeProduct.IntelliJIdeaCommunity,
                version = id.version ?: "2025.3.3",
                build = "IC-253.1",
                url = "file:///unused",
            )

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): String? = error("downloadAndUnpack should not be called by start/stop tests")
    }
}
