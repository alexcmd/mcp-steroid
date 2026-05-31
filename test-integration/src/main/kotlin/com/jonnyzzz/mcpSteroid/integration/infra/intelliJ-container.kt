/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.commitContainerToImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import java.io.File

/**
 * Controls how AI agents connect to MCP Steroid inside the test container.
 *
 * [IntelliJContainer.aiAgents] ([AiAgentDriver]) is always created regardless of mode.
 * This enum only determines which MCP transport (if any) is registered with each agent.
 *
 * Pass as `IntelliJContainer.create`'s `aiMode` parameter.
 */
enum class AiMode {
    /**
     * Agents are available, but MCP Steroid is NOT registered with them.
     * Use for pure IDE/infrastructure tests that don't need MCP Steroid tools.
     */
    NONE,

    /**
     * Agents connect to MCP Steroid via HTTP (default).
     * Each agent has AiAgentSession.registerHttpMcp called with the guest-side URL.
     */
    AI_MCP,

    /**
     * Agents connect to MCP Steroid via devrig stdio.
     * [DevrigSteroidDriver] is deployed before agents are initialized; each agent
     * has `AiAgentSession.registerStdioMcp` called with the resulting command.
     */
    AI_DEVRIG,
}

/**
 * Manages a Docker container running IntelliJ IDEA with the MCP Steroid plugin.
 * Assembles the Docker build context from separate artifacts and starts a named container.
 *
 * The container is NOT removed after the test — it stays around for debugging.
 * It IS removed before the next test run (by name).
 *
 * All IDE directories, video, and screenshots are mounted to a timestamped
 * run directory under testOutputDir for easy inspection and debugging.
 */
class IntelliJContainer(
    val lifetime: CloseableStack,
    val gui: GuiContainer,

    val runDirInContainer: File,
    val scope: ContainerDriver,

    val intellijDriver: IntelliJDriver,

    private val intellij: RunningContainerProcess,

    val mcpSteroid: McpSteroidDriver,

    /**
     * AI agent driver — always present.
     * Whether agents have MCP Steroid registered depends on the [AiMode] used at creation.
     */
    val aiAgents: AiAgentDriver,

    /**
     * Relative path (from project root) of the file to open when the IDE starts.
     * When null, the default README.md / first source file fallback is used.
     */
    private val openFileOnStart: String? = null,

    /**
     * The project this container was created for. Carries its declared JDK version and
     * build systems so [waitForProjectReady] can set the project SDK and import each build
     * system without the caller restating them.
     */
    val project: IntelliJProject,
) {
    val input: XcvbInputDriver by gui::inputDriver
    val console: ConsoleDriver by gui::console
    val windows: XcvbWindowDriver by gui::windowsDriver
    private val windowLayout: WindowLayoutManager by gui::windowsLayout

    val pid by intellij::pid

    private fun latestScreenshotPath(): String? =
        File(runDirInContainer, "screenshot")
            .listFiles { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath

    fun diagnosticsSummary(): String = buildString {
        appendLine("RUN_DIR=${runDirInContainer.absolutePath}")
        appendLine("SESSION_INFO=${File(runDirInContainer, "session-info.txt").absolutePath}")
        appendLine("SCREENSHOT_DIR=${File(runDirInContainer, "screenshot").absolutePath}")
        appendLine("VIDEO_DIR=${File(runDirInContainer, "video").absolutePath}")
        appendLine("IDE_LOG=${File(runDirInContainer, "intellij/ide-log/idea.log").absolutePath}")
        appendLine("AGENT_LOG_DIR=${runDirInContainer.absolutePath}")
        appendLine("AGENT_LOG_PATTERN=agent-<name>-<N>-raw.ndjson / agent-<name>-<N>-decoded.txt")
    }.trimEnd()

    private fun problemDetailsWithScreenshot(baseDetails: String): String {
        val screenshot = latestScreenshotPath() ?: "<none>"
        return "$baseDetails; latestScreenshot=$screenshot\n${diagnosticsSummary()}"
    }

    /**
     * Wait for the IDE project to finish import and indexing.
     * Polls via MCP execute_code until DumbService reports smart mode.
     * Writes progress to the console.
     *
     * When a modal dialog is detected (e.g. NewUI Onboarding in IntelliJ 2025.3.3+),
     * actively kills it via steroid_execute_code so Gradle import can proceed.
     *
     * Wait for the project to be fully ready for agent work.
     *
     * Ordered steps:
     * 1. Wait for IDE window (projectInitialized=true, indexingInProgress=false)
     * 2. Reposition IDE window
     * 3. Register JDKs via IntelliJ API (earliest possible — before any import)
     * 4. Set project SDK (parameter-driven, skip for Rider/.NET)
     * 5. Trigger build tool import (Maven/Gradle/NONE)
     * 6. Wait for import + indexing to complete
     * 7. Install IDE plugins (after import so dependency detection works)
     * 8. Compile project (testClasses/test-compile) — optional
     * 9. Open file + show tool windows
     *
     * @param timeoutMillis Max time for initial IDE window wait
     * @param projectJdkVersion JDK version to set as project SDK ("21", "17", etc.), null = skip
     * @param buildSystem Build system for import/compile. NONE = IntelliJ auto-detect only
     * @param compileProject Whether to run compilation (testClasses/test-compile) before returning
     */
    fun waitForProjectReady(
        timeoutMillis: Long = System.getProperty("test.integration.project.ready.timeout.ms")?.toLongOrNull() ?: 600_000L,
        pollIntervalMillis: Long = 1_000L,
        requireIndexingComplete: Boolean = true,
        performPostSetup: Boolean = true,
        projectJdkVersion: String? = project.jdkVersion,
        /**
         * Build system to import. When null (default) the project's declared [IntelliJProject.buildSystems]
         * are imported (each with its own root); pass an explicit value to override.
         */
        buildSystem: BuildSystem? = null,
        compileProject: Boolean = false,
    ) : IntelliJContainer {
        // Step 1: Wait for IDE window
        val waitLabel = if (requireIndexingComplete) "project import and indexing" else "project initialization"
        console.writeStep(1, "Waiting for $waitLabel...")
        val guestProjectDir = intellijDriver.getGuestProjectDir()
        waitForIdeWindow(guestProjectDir, timeoutMillis, pollIntervalMillis, requireIndexingComplete, waitLabel)

        if (!performPostSetup) return this

        // Step 3: Register JDKs (earliest — before any import)
        // Only for Java-capable IDEs: `mcpRegisterJdks` / `mcpSetProjectSdk` use
        // `JavaSdk` which isn't on the script classpath in PyCharm/GoLand/WebStorm/Rider.
        if (projectJdkVersion != null && intellijDriver.ideProduct.hasJavaSdk) {
            console.writeStep(3, "Registering JDKs via IntelliJ API...")
            mcpSteroid.mcpRegisterJdks()
            console.writeSuccess("JDK registration complete")

            // Step 4: Set project SDK
            console.writeStep(4, "Setting project SDK to JDK $projectJdkVersion...")
            mcpSteroid.mcpSetProjectSdk(projectJdkVersion)
            console.writeSuccess("Project SDK set to $projectJdkVersion")
        } else if (projectJdkVersion != null) {
            console.writeStep(3, "Skipping JDK setup — ${intellijDriver.ideProduct.displayName} has no Java plugin")
        } else {
            console.writeStep(3, "Skipping JDK setup (projectJdkVersion=null)")
        }

        // Step 5+6: Import each declared build system and wait for completion.
        // An explicit buildSystem arg overrides; otherwise import the project's declared set.
        // No build systems -> nothing to import (and we must NOT call awaitConfiguration on a
        // project with an unconfigured external build, which is what stalled for ~8 min).
        val systemsToImport: List<BuildSystem> = when {
            buildSystem != null -> listOf(buildSystem).filter { it != BuildSystem.NONE }
            else -> project.buildSystems.map { it.type }.distinct()
        }
        if (systemsToImport.isEmpty()) {
            console.writeStep(5, "No build system to import — skipping import wait")
        } else {
            systemsToImport.forEach { bs ->
                console.writeStep(5, "Triggering $bs import and waiting...")
                mcpSteroid.mcpTriggerImportAndWait(bs)
            }
            console.writeSuccess("Import + indexing complete")
        }

        // Step 6b: Resolve unknown SDKs (prevents "Resolving SDKs..." false positive during build)
        console.writeStep(6, "Resolving unknown SDKs...")
        mcpSteroid.mcpResolveUnknownSdks()
        console.writeSuccess("SDK resolution complete")

        // Step 7: Install IDE plugins
        console.writeStep(7, "Installing required IDE plugins...")
        mcpSteroid.mcpInstallRequiredPlugins()
        console.writeSuccess("Plugin installation complete")

        // Step 8: Compile project (optional)
        if (compileProject) {
            val compileWith = systemsToImport.firstOrNull() ?: BuildSystem.NONE
            console.writeStep(8, "Compiling project ($compileWith)...")
            mcpSteroid.mcpCompileProject(compileWith, projectJdkVersion)
            console.writeSuccess("Compilation complete")
        }

        // Step 9: Open file + show tool windows
        console.writeStep(9, "Opening project file and build tool window...")
        mcpSteroid.mcpOpenFileAndBuildToolWindow(openFileOnStart)
        console.writeSuccess("Project UX ready")

        return this
    }

    /**
     * Poll for IDE window readiness (extracted from the old waitForProjectReady).
     */
    private fun waitForIdeWindow(
        guestProjectDir: String,
        timeoutMillis: Long,
        pollIntervalMillis: Long,
        requireIndexingComplete: Boolean,
        waitLabel: String,
    ) {
        val startedAt = System.currentTimeMillis()
        var lastStatus = "no project windows found"
        var projectReady = false
        var lastHeartbeatAt = startedAt

        // Surface poll status every ~10 s so silent multi-minute waits do not
        // look identical to a hung wait. CLAUDE.md's "1-minute investigate"
        // rule depends on operators seeing some output between polls.
        fun heartbeatIfDue() {
            val now = System.currentTimeMillis()
            if (now - lastHeartbeatAt >= 10_000L) {
                console.writeInfo(
                    "Still waiting for $waitLabel: $lastStatus (elapsed=${(now - startedAt) / 1000}s)"
                )
                lastHeartbeatAt = now
            }
        }

        while (System.currentTimeMillis() - startedAt < timeoutMillis) {
            val windows = try {
                mcpSteroid.mcpListWindows(timeoutSeconds = 120)
            } catch (e: Exception) {
                lastStatus = "mcpListWindows failed: ${e.message}"
                heartbeatIfDue()
                Thread.sleep(pollIntervalMillis)
                continue
            }
            val projectWindows = windows.filter { it.projectPath == guestProjectDir || it.projectName != null }

            if (projectWindows.isEmpty()) {
                lastStatus = "no project windows found"
                heartbeatIfDue()
                Thread.sleep(pollIntervalMillis)
                continue
            }

            val modalDialogPresent = projectWindows.any { it.modalDialogShowing }
            if (modalDialogPresent) {
                // Fail fast — retrying past an unexpected modal hides real problems. Every
                // startup modal in our tests is an infrastructure bug (unknown SDK →
                // "download Corretto?" consent, "Open or Import?" prompt, etc.) that must
                // be fixed by pre-configuring the IDE so the dialog never fires in the
                // first place. `killStartupDialogs` was a workaround that let failures
                // linger; now the test surfaces them immediately with a screenshot.
                error(
                    "Blocking modal dialog detected while waiting for $waitLabel. " +
                            "This is an infrastructure bug — modals must be prevented up-front (e.g. seed " +
                            "`ProjectJdkTable` via `mcpRegisterJdks` in the factory, pre-write trusted paths, " +
                            "suppress welcome dialogs), not killed reactively. " +
                            problemDetailsWithScreenshot("projectWindows=${projectWindows.size}")
                )
            }

            val readyWindow = projectWindows.any { window ->
                val initialized = window.projectInitialized == true
                val indexingDone = window.indexingInProgress == false
                initialized && (!requireIndexingComplete || indexingDone)
            }
            if (readyWindow) {
                console.writeSuccess(
                    if (requireIndexingComplete) "Project import and indexing complete"
                    else "Project initialized"
                )
                projectReady = true
                break
            }

            val initialized = projectWindows.any { it.projectInitialized == true }
            val indexing = projectWindows.any { it.indexingInProgress == true }
            lastStatus = "projectInitialized=$initialized, indexingInProgress=$indexing, windows=${projectWindows.size}"
            heartbeatIfDue()
            Thread.sleep(pollIntervalMillis)
        }

        val elapsed = System.currentTimeMillis() - startedAt
        require(projectReady) {
            "Failed waiting for $waitLabel after ${elapsed}ms. " +
                    problemDetailsWithScreenshot("Last status: $lastStatus")
        }
    }

    /**
     * Re-apply the IDE window layout by finding the IntelliJ window by PID and calling
     * [XcvbWindowDriver.updateLayout] with the target rect from [windowLayout].
     *
     * IntelliJ restores its own saved window bounds after project load, overriding the
     * initial xdotool positioning. This must be called after project initialization completes.
     */
     fun repositionIdeWindow() {
        console.writeStep(1, "Applying IDE window layout...")
        val ideWindow = windows.listWindows().firstOrNull { it.pid == pid }
        if (ideWindow == null) {
            println("[IDE-AGENT] repositionIdeWindow: no window found for PID=$pid, skipping")
            return
        }
        val targetRect = windowLayout.layoutIntelliJWindow()
        windows.updateLayout(ideWindow, targetRect)
        // Nudge the window size by 1px then restore: IntelliJ AWT may not notice the external
        // move and keeps rendering at the old position (50px gap at top, status bar clipped).
        // A second ConfigureNotify with a different size forces AWT to re-layout correctly.
        windows.forceRelayout(ideWindow, targetRect)
        console.writeSuccess("Window layout applied")
    }

    companion object
}
