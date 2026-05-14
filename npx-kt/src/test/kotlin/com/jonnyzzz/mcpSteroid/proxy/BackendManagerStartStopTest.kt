/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

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
        val manager = BackendManager(homePaths, downloader = StaticDownloader)

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))

        assertTrue(started.pid > 0)
        assertEquals("${started.pid}\n", Files.readString(homePaths.pidFile("idea-community-2025.3.3")))
        assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)

        val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

        assertEquals("stopped", stopped.outcome)
        assertFalse(homePaths.pidFile("idea-community-2025.3.3").exists())
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `stop force kills a process that does not exit before the grace period`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = sleepyLauncher())
        val manager = BackendManager(homePaths, downloader = StaticDownloader, stopGracePeriodMillis = 0L)

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
            acceptPaid: Boolean,
        ): String? = error("downloadAndUnpack should not be called by start/stop tests")
    }
}
