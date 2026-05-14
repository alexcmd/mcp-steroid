/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndForget
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ManagedBackendDockerIntegrationTest {
    companion object {
        private val lifetime by lazy { CloseableStackHost(ManagedBackendDockerIntegrationTest::class.java.simpleName) }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `devrig downloads starts and stops an isolated IDEA Community backend`() {
        val container = createContainer()
        startDisplay(container)

        val download = container.exec("download IDEA Community") {
            listOf("devrig", "--home", "/tmp/mcp-home", "backend", "download", "idea-community")
        }.assertExitCode(0) { "download failed\nstdout=$stdout\nstderr=$stderr" }
        assertTrue(download.stdout.contains("id: idea-community-"), download.stdout)

        val id = container.exec("resolve backend id") {
            listOf("bash", "-lc", "basename \"$(ls -d /tmp/mcp-home/backends/idea-community-* | head -1)\"")
        }.assertExitCode(0).stdout.trim()
        assertTrue(Regex("""idea-community-\d+\.\d+.*""").matches(id), id)

        container.exec("assert managed backend files") {
            listOf(
                "bash", "-lc",
                """
                set -euo pipefail
                bundle="${'$'}(find "/tmp/mcp-home/backends/$id" -mindepth 1 -maxdepth 1 -type d | head -1)"
                test -n "${'$'}bundle"
                test -f "/tmp/mcp-home/backends/$id/${'$'}(basename "${'$'}bundle").vmoptions"
                grep -F -- "-Didea.config.path=/tmp/mcp-home/caches/$id/config" "/tmp/mcp-home/backends/$id/${'$'}(basename "${'$'}bundle").vmoptions"
                grep -F -- "-Didea.system.path=/tmp/mcp-home/caches/$id/system" "/tmp/mcp-home/backends/$id/${'$'}(basename "${'$'}bundle").vmoptions"
                grep -F -- "-Didea.log.path=/tmp/mcp-home/caches/$id/logs" "/tmp/mcp-home/backends/$id/${'$'}(basename "${'$'}bundle").vmoptions"
                grep -F -- "-Didea.plugins.path=/tmp/mcp-home/caches/$id/plugins" "/tmp/mcp-home/backends/$id/${'$'}(basename "${'$'}bundle").vmoptions"
                """.trimIndent(),
            )
        }.assertExitCode(0)

        val start = container.exec("start managed backend") {
            listOf("devrig", "--home", "/tmp/mcp-home", "backend", "start", "idea-community")
        }.assertExitCode(0) { "start failed\nstdout=$stdout\nstderr=$stderr" }
        assertTrue(Regex("""pid: \d+""").containsMatchIn(start.stdout), start.stdout)

        container.exec("wait for IDEA api-about") {
            listOf(
                "bash", "-lc",
                """
                set -euo pipefail
                deadline=${'$'}((SECONDS + 90))
                while [ "${'$'}SECONDS" -lt "${'$'}deadline" ]; do
                  for port in ${'$'}(seq 63342 63361); do
                    body="${'$'}(curl -fsS "http://127.0.0.1:${'$'}port/api/about" 2>/dev/null || true)"
                    if echo "${'$'}body" | grep -q '"productName"[[:space:]]*:[[:space:]]*"IDEA"'; then
                      echo "${'$'}body"
                      exit 0
                    fi
                  done
                  sleep 2
                done
                echo "IDEA did not answer /api/about" >&2
                exit 1
                """.trimIndent(),
            )
        }.assertExitCode(0)

        container.exec("assert vmoptions were honored") {
            listOf(
                "bash", "-lc",
                """
                set -euo pipefail
                test -d "/tmp/mcp-home/caches/$id/config"
                test -d "/tmp/mcp-home/caches/$id/system"
                test -d "/tmp/mcp-home/caches/$id/logs"
                test -d "/tmp/mcp-home/caches/$id/plugins"
                test "$(find "/tmp/mcp-home/caches/$id/logs" -type f | wc -l)" -gt 0
                """.trimIndent(),
            )
        }.assertExitCode(0)

        container.exec("stop managed backend") {
            listOf("devrig", "--home", "/tmp/mcp-home", "backend", "stop", "idea-community")
        }.assertExitCode(0)

        container.exec("assert IDEA process is gone") {
            listOf("bash", "-lc", "! pgrep -f 'com.intellij.idea.Main|idea.sh|/idea-'")
        }.assertExitCode(0)
    }

    private fun createContainer(): ContainerDriver {
        val runDir = Files.createTempDirectory("managed-backends-docker-").toFile()
        lifetime.registerCleanupAction { runDir.deleteRecursively() }

        val base = buildDockerImage(
            logPrefix = "managed-backends-base",
            dockerfilePath = File(IdeTestFolders.dockerDir, "ide-base/Dockerfile"),
            timeoutSeconds = 600,
            quietly = true,
        )

        val context = runDir.resolve("docker").apply { mkdirs() }
        File(IdeTestFolders.dockerDir, "managed-backend-cli/Dockerfile").copyTo(context.resolve("Dockerfile"))
        IdeTestFolders.npxKtPackageZip.copyTo(context.resolve("mcp-steroid-proxy.zip"))
        val image = buildDockerImage(
            logPrefix = "managed-backends-cli",
            dockerfilePath = context.resolve("Dockerfile"),
            timeoutSeconds = 300,
            buildArgs = mapOf("BASE_IMAGE" to base.imageSha256),
            quietly = true,
        )
        val container = startDockerContainerAndForget(
            com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest()
                .logPrefix("managed-backends")
                .image(image)
                .autoRemove(true)
                .quietly(),
        )
        lifetime.registerCleanupAction {
            container.newRunOnHost()
                .command("docker", "rm", "-f", container.containerId)
                .description("Remove managed backends container")
                .quietly()
                .startProcess()
                .awaitForProcessFinish()
        }
        return container
    }

    private fun startDisplay(container: ContainerDriver) {
        container.exec("start Xvfb and fluxbox") {
            listOf(
                "bash", "-lc",
                "Xvfb :99 -screen 0 1920x1080x24 >/tmp/xvfb.log 2>&1 & fluxbox >/tmp/fluxbox.log 2>&1 &",
            )
        }.assertExitCode(0)
    }

    private fun ContainerDriver.exec(description: String, command: () -> List<String>) =
        startProcessInContainer {
            args(command())
                .description(description)
                .timeoutSeconds(300)
        }.awaitForProcessFinish()
}
