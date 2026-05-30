package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessStreamType
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

data class DevrigContainerOpts(
    val consoleTitle: String,
    val dockerFileBase: String = "managed-backend-host",
    val layoutManager: LayoutManager = HorizontalLayoutManager(),
)

class DevrigContainer(
    val scope: ContainerDriver,
    val gui: GuiContainer,
    val devrig: String,
) {
    val console: ConsoleDriver by gui::console

    companion object

    fun execAndAssert(description: String,
                              script: String,
                              timeoutSeconds: Long = 300,
    ) = execAndAssert(scope, description, script, timeoutSeconds)

    /**
     * Runs a devrig script inside this container while live-streaming stdout/stderr
     * to the on-video xterm. The full transcript is still returned for assertions.
     */
    fun execAndAssertWithConsoleStream(
        description: String,
        script: String,
        timeoutSeconds: Long = 120,
    ): ProcessResult {
        console.writeHeader(description)

        val wrappedScript = """
        set -euo pipefail
        (
        ${script.trimIndent()}
        ) 2>&1
    """.trimIndent()

        val streamedStdoutLines = AtomicInteger(0)
        val streamedStderrLines = AtomicInteger(0)
        val process = scope.startProcessInContainer {
            args("bash", "-lc", wrappedScript)
                .timeoutSeconds(timeoutSeconds)
                .description(description)
        }

        val streamer = thread(
            start = true,
            isDaemon = true,
            name = "devrig-console-stream-${System.nanoTime()}",
        ) {
            try {
                runBlocking {
                    process.messagesFlow.collect { line ->
                        when (line.type) {
                            ProcessStreamType.STDOUT -> {
                                streamedStdoutLines.incrementAndGet()
                                console.writeLine("${ConsoleDriver.GREEN}[devrig]${ConsoleDriver.RESET} ${line.line}")
                            }

                            ProcessStreamType.STDERR -> {
                                streamedStderrLines.incrementAndGet()
                                console.writeLine("${ConsoleDriver.RED}[devrig]${ConsoleDriver.RESET} ${line.line}")
                            }

                            ProcessStreamType.INFO -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Failed to stream devrig output to the visible console: ${e.message}")
                console.writeError("Failed to stream devrig output: ${e.message}")
            }
        }

        val result = process.awaitForProcessFinish()
        try {
            streamer.join(3_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            System.err.println("Interrupted while waiting for devrig console streamer: ${e.message}")
        }

        result.stdout.lineSequence()
            .drop(streamedStdoutLines.get())
            .filter { line -> line.isNotEmpty() }
            .forEach { line -> console.writeLine("${ConsoleDriver.GREEN}[devrig]${ConsoleDriver.RESET} $line") }
        result.stderr.lineSequence()
            .drop(streamedStderrLines.get())
            .filter { line -> line.isNotEmpty() }
            .forEach { line -> console.writeLine("${ConsoleDriver.RED}[devrig]${ConsoleDriver.RESET} $line") }

        return result.assertExitCode(0) { "$description failed\nstdout=$stdout\nstderr=$stderr" }
    }
}

fun DevrigContainer.Companion.create(lifetime: CloseableStack, opts: DevrigContainerOpts) : DevrigContainer {
    val (runDir, realConsoleTitle) = allocRunDirAndTitle(lifetime, opts.consoleTitle)

    val imageId = run {
        // Unique suffix ensures parallel test runs each build their own image and context dir,
        // preventing races in buildIdeImage when multiple tests start concurrently.
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        val imageName = "${opts.dockerFileBase}-test-$uniqueSuffix"
        buildDevrigImage(opts.dockerFileBase, imageName)
    }

    val containerMountedPath = "/mcp-run-dir"

    val volumes = buildList {
        add(ContainerVolume(runDir, containerMountedPath, "rw"))
    }

    val containerEnv = buildMap<String, String> {
    }

    var container: ContainerDriver = startDockerContainerAndDispose(
        lifetime,
        StartContainerRequest()
            .image(imageId)
            .enableInit()
            .extraEnvVars(containerEnv)
            .volumes(volumes)
            .ports(
                XcvbVideoDriver.VIDEO_STREAMING_PORT,
                McpSteroidDriver.MCP_STEROID_PORT,
                IDE_DEBUG_PORT,
                DEVRIG_DEBUG_PORT,
            ),
    )

    val gui = setupGuiContainerServices(lifetime, container, opts.layoutManager, containerMountedPath, realConsoleTitle)
    val console = gui.console
    container = gui.container

    console.writeInfo("Preparing devrig...")

    val devrigLauncher = deployDevrigLauncher(container)
    val driver = DevrigContainer(
        container,
        gui,
        devrigLauncher,
    )

    return driver
}


private fun deployDevrigLauncher(scope: ContainerDriver, packageZip: File = IdeTestFolders.devrigPackageZip): String {
    require(packageZip.isFile) { "devrig distribution ZIP does not exist: ${packageZip.absolutePath}" }

    val launcherPath = "/home/agent/devrig"
    scope.copyToContainer(packageZip, "/tmp/devrig.zip")
    execAndAssert(
        scope,
        description = "install devrig launcher",
        timeoutSeconds = 120,
        script = $$"""
                set -euo pipefail
                rm -rf /home/agent/devrig-cli "$$launcherPath"
                mkdir -p /home/agent/devrig-cli
                unzip -q /tmp/devrig.zip -d /home/agent/devrig-cli
                app_dir="$(find /home/agent/devrig-cli -mindepth 1 -maxdepth 1 -type d -name 'devrig-*' | head -1)"
                test -n "$app_dir"
                mv "$app_dir" /home/agent/devrig-cli/app
                chmod +x /home/agent/devrig-cli/app/bin/devrig
                ln -sfn devrig /home/agent/devrig-cli/app/bin/devrig
                ln -sfn /home/agent/devrig-cli/app/bin/devrig "$$launcherPath"
                "$$launcherPath" --version
            """.trimIndent(),
    )
    return launcherPath
}

private fun execAndAssert(
    scope: ContainerDriver,
    description: String,
    script: String,
    timeoutSeconds: Long = 300,
): ProcessResult {
    return scope.startProcessInContainer {
        this
            .args("bash", "-lc", script)
            .timeoutSeconds(timeoutSeconds)
            .description(description)
    }.awaitForProcessFinish()
        .assertExitCode(0) { "$description failed\nstdout=$stdout\nstderr=$stderr" }
}
