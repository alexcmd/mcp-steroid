/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.ideDownloader.ideStartupConfigFiles
import com.jonnyzzz.mcpSteroid.ideDownloader.ideUserStartupConfigFiles
import com.jonnyzzz.mcpSteroid.integration.infra.McpSteroidDriver.Companion.MCP_STEROID_PORT
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.deployZipAndUnpack
import com.jonnyzzz.mcpSteroid.testHelper.docker.mapGuestPathToHostPath
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import com.jonnyzzz.mcpSteroid.testHelper.docker.runInContainerDetached
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.jdom2.input.SAXBuilder
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import java.io.File
import java.io.StringReader
import kotlin.concurrent.thread

/**
 * JDWP debug port the in-container IDE JVM always listens on (`server=y,suspend=n`),
 * exposed through Docker and printed to the test output + `session-info.txt` as
 * `IDE_DEBUG_PORT=<host-port>`. Attach IntelliJ's "Remote JVM Debug" run config to
 * `localhost:<host-port>` to debug the IDE (and the MCP Steroid plugin) live while a
 * test runs. `suspend=n` means the IDE never waits for a debugger, so normal runs are
 * unaffected. See test-integration/CLAUDE.md → "Remote-debugging the Dockerized IDE".
 */
val IDE_DEBUG_PORT = ContainerPort(5005)

/**
 * JDWP debug port the in-container **devrig** (`npx-kt`) JVM listens on when deployed as the
 * agents' stdio MCP bridge (`devrig mpc`). A *different* port from [IDE_DEBUG_PORT] so the IDE
 * and devrig can be debugged simultaneously. Exposed through Docker and printed to the test
 * output as `DEVRIG_DEBUG_PORT=<host-port>` plus the standard JVM "Listening for transport …"
 * line with the host-mapped port. `suspend=n` so devrig never waits; `quiet=y` so the JDWP
 * agent does NOT print its own "Listening …" line to stdout — that would corrupt the stdio
 * JSON-RPC channel `devrig mpc` runs on. The host-side print (see [DevrigSteroidDriver.deploy])
 * substitutes for it. Opts are injected via the app-specific `DEVRIG_OPTS` env var (NOT
 * `JAVA_TOOL_OPTIONS`, which would leak into child JVMs and double-bind this port).
 */
val DEVRIG_DEBUG_PORT = ContainerPort(5006)

class IntelliJDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val guestDir: String,
    val ideProduct: IdeProduct,
    private val skipChangedFilesScanOnStartup: Boolean = false,
    private val disableProjectTrustChecks: Boolean = true,
    private val trustAllProjectPaths: Boolean = true,
    private val preloadJdkTable: Boolean = true,
) {
    private val intelliJGuestHomeDir = "/opt/idea"
    // Keep project sources on container-local filesystem (not host-mounted volume)
    // so Docker snapshots can capture fully indexed project state consistently.
    private val projectGuestDir = "/home/agent/project-home"
    private val configGuestDir = "$guestDir/ide-config"
    private val systemGuestDir = "/home/agent/ide-system"
    private val logsGuestDir = "$guestDir/ide-log"
    // Plugins live OFF the /mcp-run-dir bind mount — same rationale as
    // systemGuestDir. The unpacked plugin tree (~185 MB, dominated by
    // kotlin-compiler.jar and the bundled prompts) would otherwise be
    // dragged into every TC artifact and run-*.zip. Keeping it container-
    // local costs nothing (fresh unzip per run) and keeps CI artifacts slim.
    private val pluginsGuestDir = "/home/agent/ide-plugins"
    private val steroidGuestDir = "$guestDir/mcp-steroid"

    fun getGuestProjectDir() = projectGuestDir
    fun getGuestSystemDir() = systemGuestDir
    fun getGuestConfigDir() = configGuestDir
    fun getGuestPluginsDir() = pluginsGuestDir

    fun readLogs(): List<String> {
        val file = ideaLogsFile()
        if (!file.exists()) return emptyList()
        return file.readLines()
    }

    private fun ideaLogsFile(): File = driver.mapGuestPathToHostPath(logsGuestDir).resolve("idea.log")

    fun startIde(beforeIdeStart: List<IntelliJDriver.() -> Unit> = emptyList()): RunningContainerProcess {
        driver.mkdirs(intelliJGuestHomeDir)
        driver.mkdirs(projectGuestDir)
        driver.mkdirs(configGuestDir)
        driver.mkdirs(systemGuestDir)
        driver.mkdirs(logsGuestDir)
        driver.mkdirs(pluginsGuestDir)

        driver.startProcessInContainer {
            this
                .args("ls", "-la", intelliJGuestHomeDir)
                .description("ls -la $intelliJGuestHomeDir")
        }.awaitForProcessFinish()

        writeUserStartupConfigFiles()
        writeTrustedPaths()
        writeStartupConfigFiles()
        generateVmOptions()
        // Pre-write the JDK table BEFORE the IDE launches so Gradle auto-import resolves the
        // project JDK at project-open time. Registering JDKs only AFTER the IDE is up (via
        // mcpRegisterJdks) loses the race against project-open's Gradle sync — which then can't
        // resolve its JDK and stalls ~8 min on `Observation.awaitConfiguration`. The VM options
        // `-Dunknown.sdk*=false` still suppress any download-consent modal as belt-and-suspenders.
        // The XML is generated by infra code (see jdk-table-xml.kt), mirroring IntelliJ's own
        // JavaSdkImpl/ProjectJdkImpl serialization; a JdkTableIntegrationTest validates the match.
        writeJdkTable()

        // Before-start hooks: let callers tweak the IDE config / project files while no IDE runs yet.
        beforeIdeStart.forEach { it(this) }

        driver.log("Starting ${ideProduct.displayName}...")
        val launcherPath = "$intelliJGuestHomeDir/bin/${ideProduct.launcherExecutable}"
        driver.startProcessInContainer {
            this
                .args("ls", "-la", "$intelliJGuestHomeDir/bin")
                .description("ls -la $intelliJGuestHomeDir/bin")
        }.awaitForProcessFinish()

        // Rider requires the .sln file path (not the directory) to skip the
        // "Select a Solution to Open" dialog that blocks the main window from appearing.
        val launchTarget = if (ideProduct == IdeProduct.Rider) {
            val slnFile = findSlnFile(projectGuestDir)
            if (slnFile != null) {
                driver.log("Rider: opening solution file $slnFile")
                slnFile
            } else {
                driver.log("Rider: no .sln file found, falling back to directory")
                projectGuestDir
            }
        } else {
            projectGuestDir
        }

        val idea = driver.runInContainerDetached(
            listOf(launcherPath, launchTarget),
        )

        val logFile = ideaLogsFile()
        //make sure the log file is here
        runCatching { logFile.parentFile.mkdirs() }
        runCatching { logFile.appendText("\n\nStarted MCP Steroid Integration test\n\n") }

        if (!idea.isRunning()) {
            idea.printProcessInfo()
            throw RuntimeException("${ideProduct.displayName} exited unexpectedly with code ${idea.exitCode}. See logs above for details.")
        }

        val ijLogsStream = thread(isDaemon = true, name = "ijLogsStream") {
            runCatching {
                logFile.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine()
                        if (line == null) {
                            Thread.sleep(100)
                            continue
                        }
                        println("[IntelliJ LOG] $line")
                    }
                }
            }
        }

        lifetime.registerCleanupAction {
            ijLogsStream.interrupt()
        }

        return idea
    }

    private fun generateVmOptions() {
        val vmXmx = System.getProperty("test.integration.ide.vm.xmx")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "6g"
        val vmXms = System.getProperty("test.integration.ide.vm.xms")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "1g"

        val opts = buildString {
            appendLine("-Xmx$vmXmx")
            appendLine("-Xms$vmXms")

            // JDWP debug agent — always on, never suspends (server=y,suspend=n). Lets a
            // developer attach IntelliJ's "Remote JVM Debug" to localhost:<mapped IDE_DEBUG_PORT>
            // (printed to the test output + session-info.txt) to debug the IDE + MCP Steroid
            // plugin live while a Docker test runs. address=*: so it binds all interfaces inside
            // the container (Docker maps it to the host). See test-integration/CLAUDE.md.
            //
            // MUST stay suspend=n for :test-integration AND :test-experiments (both share this
            // infra). suspend=y would block the IDE's main thread until a debugger attaches —
            // on CI nobody attaches, so the whole test would hang until its timeout. NEVER flip
            // this to suspend=y; set breakpoints + attach live instead (the agent stays open).
            appendLine("# Remote JVM debug (attach to mapped IDE_DEBUG_PORT; suspend=n so the IDE never waits)")
            appendLine("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${IDE_DEBUG_PORT.containerPort}")

            appendLine("# Redirect IDE directories to explicit paths")
            appendLine("-Didea.config.path=$configGuestDir")
            appendLine("-Didea.system.path=$systemGuestDir")
            appendLine("-Didea.log.path=$logsGuestDir")
            appendLine("-Didea.plugins.path=$pluginsGuestDir")
            if (skipChangedFilesScanOnStartup) {
                appendLine("# Warm snapshot startup: skip changed-files scan to avoid re-indexing")
                appendLine("-Didea.indexes.pretendNoFiles=true")
            }

            appendLine("# MCP Steroid plugin configuration")
            appendLine("-Dmcp.steroid.server.host=0.0.0.0")
            appendLine("-Dmcp.steroid.server.port=${MCP_STEROID_PORT.containerPort}")
            appendLine("-Dmcp.steroid.dialog.killer.enabled=true")
            appendLine("-Dmcp.steroid.updates.enabled=false")
            appendLine("-Dmcp.steroid.analytics.enabled=false")
            appendLine("-Dmcp.steroid.idea.description.enabled=false")
            appendLine("-Dmcp.steroid.storage.path=$steroidGuestDir")

            appendLine("# Suppress AI promo window (prevents 8-minute startup deadlock in Docker)")
            appendLine("# AIPromoWindowAdvisor fetches a remote URL on first run; in Docker this")
            appendLine("# times out after 480s and blocks VfsData via fleet.kernel.Transactor.")
            appendLine("-Dllm.show.ai.promotion.window.on.start=false")
            appendLine("# Reduce network connection timeout (safety net: cuts any remaining")
            appendLine("# timeout-based delay from ~480s down to ~15s: 5 retries x 3s).")
            appendLine("-Didea.connection.timeout=3000")
            appendLine()
            appendLine("# License server for commercial IDEs (Rider, etc.)")
            appendLine("-DJETBRAINS_LICENSE_SERVER=https://flsv1.labs.jb.gg")
            appendLine()
            appendLine("# Skip EULA, consent dialogs, trust prompts, and onboarding")
            if (disableProjectTrustChecks) {
                appendLine("-Didea.trust.disabled=true")
            }
            appendLine("-Djb.consents.confirmation.enabled=false")
            appendLine("-Djb.privacy.policy.text=<!--999.999-->")
            appendLine("-Djb.privacy.policy.ai.assistant.text=<!--999.999-->")
            appendLine("-Dmarketplace.eula.reviewed.and.accepted=true")
            appendLine("-Dwriterside.eula.reviewed.and.accepted=true")
            appendLine("-Didea.initially.ask.config=never")
            appendLine("-Dide.newUsersOnboarding=false")
            appendLine("-Dnosplash=true")
            appendLine()
            appendLine("# Belt-and-suspenders vs the Corretto-download consent modal:")
            appendLine("# * UnknownSdkTracker.isEnabled() (UnknownSdkTracker.java:57) short-circuits when")
            appendLine("#   `unknown.sdk` is false — no tracker activity at all, no download fixes ever created.")
            appendLine("# * UnknownSdkTracker.createCollector() (UnknownSdkTracker.java:76) returns null when")
            appendLine("#   `unknown.sdk.auto` is false — tracker exists but never runs the lookup, so")
            appendLine("#   UnknownSdkTrackerQueue.queue skips onLookupCompleted (UnknownSdkTrackerQueue.kt:44)")
            appendLine("#   and never instantiates UnknownSdkFixActionDownloadBase → no collectConsent modal.")
            appendLine("# Default for both is true; we force-disable so the Docker IDE cannot prompt.")
            appendLine("# * CompilerDriverUnknownSdkTracker.fixSdkSettings (CompilerDriverUnknownSdkTracker.kt:41)")
            appendLine("#   short-circuits when `unknown.sdk.modal.jps` is false — without this flag,")
            appendLine("#   ProjectTaskManager.build(...) fires a `Task.WithResult` with title")
            appendLine("#   'Resolving SDKs…' (modal), which our ModalityStateMonitor cancels the exec on.")
            appendLine("# `mcpRegisterJdks` (invoked from IntelliJ_factoryKt.create after waitForMcpReady)")
            appendLine("# is the primary mechanism — this trio of flags is the safety net.")
            appendLine("-Dunknown.sdk=false")
            appendLine("-Dunknown.sdk.auto=false")
            appendLine("-Dunknown.sdk.modal.jps=false")
            appendLine()
            appendLine("# Suppress telemetry, update checks, and async network startup activities")
            appendLine("-Didea.suppress.statistics.report=true")
            appendLine("-Didea.local.statistics.without.report=true")
            appendLine("-Dfeature.usage.event.log.send.on.ide.close=false")
            appendLine("-Dide.enable.notification.trace.data.sharing=false")
            appendLine("-Didea.updates.url=http://127.0.0.1")
            appendLine("-Dide.no.platform.update=true")
            appendLine("-Dide.browser.disabled=true")
            appendLine()
        }

        driver.writeFileInContainer("$intelliJGuestHomeDir.vmoptions", opts)
    }

    private fun writeUserStartupConfigFiles() {
        for (file in ideUserStartupConfigFiles()) {
            driver.writeFileInContainer("/home/agent/${file.relativePath}", file.content)
        }
    }

    private fun writeStartupConfigFiles() {
        for (file in ideStartupConfigFiles()) {
            driver.writeFileInContainer("$configGuestDir/${file.relativePath}", file.content)
        }
    }

    private fun writeTrustedPaths() {
        //TODO: must use the API to write that into running IDE, instead of the XML approach
        val trustedPathsXml = buildString {
            appendLine("""<application>""")
            appendLine("""  <component name="Trusted.Paths">""")
            appendLine("""    <option name="TRUSTED_PROJECT_PATHS">""")
            appendLine("""      <map>""")
            appendLine("""        <entry key="$projectGuestDir" value="true" />""")
            appendLine("""      </map>""")
            appendLine("""    </option>""")
            appendLine("""  </component>""")
            if (trustAllProjectPaths) {
                appendLine("""  <component name="Trusted.Paths.Settings">""")
                appendLine("""    <option name="TRUSTED_PATHS">""")
                appendLine("""      <list>""")
                appendLine("""        <option value="/" />""")
                appendLine("""      </list>""")
                appendLine("""    </option>""")
                appendLine("""  </component>""")
            }
            appendLine("""</application>""")
        }

        driver.writeFileInContainer(
            "$configGuestDir/options/trusted-paths.xml",
            trustedPathsXml,
        )
    }

    /**
     * Pre-write `options/jdk.table.xml` from infra code so the JDK table is populated before
     * the IDE starts. Only Java-capable IDEs bundle the JavaSDK type; for others there is no
     * JDK table to seed. See [generateJdkTableXml].
     */
    private fun writeJdkTable() {
        if (!preloadJdkTable) {
            driver.log("Skipping jdk.table.xml pre-write — preloadJdkTable=false")
            return
        }
        if (!ideProduct.hasJavaSdk) {
            driver.log("Skipping jdk.table.xml pre-write — ${ideProduct.displayName} has no Java plugin")
            return
        }
        val xml = generateJdkTableXml(driver)
        // jdk.table.xml is large (~200KB+). writeFileInContainer streams via a `bash -c` heredoc
        // with a short timeout and chokes on that size, so write straight to the host-mapped path
        // (configGuestDir lives under the host-mounted run dir).
        val hostFile = driver.mapGuestPathToHostPath("$configGuestDir/options/jdk.table.xml")
        hostFile.parentFile?.mkdirs()
        hostFile.writeText(xml)
        driver.log("Pre-wrote jdk.table.xml (${xml.length} chars) into $configGuestDir/options")
    }

    /**
     * Find a .sln file in the guest project directory.
     * Lists files in the container and returns the first .sln path found, or null.
     */
    private fun findSlnFile(guestProjectDir: String): String? {
        //TODO: make sure guestProjectDir is the path to the project file, not the dir
        val result = driver.startProcessInContainer {
            this
                .args("bash", "-c", "ls $guestProjectDir/*.sln 2>/dev/null")
                .timeoutSeconds(5)
                .quietly()
                .description("find .sln in $guestProjectDir")
        }.assertExitCode(0) {
            "Failed to find .sln file in $guestProjectDir"
        }

        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.endsWith(".sln") }
    }

    /**
     * Pin the Gradle JVM in the project's `.idea/gradle.xml` to a registered JDK name BEFORE the
     * IDE starts, so project-open Gradle auto-import resolves its JVM instead of stalling on an
     * unresolved `gradleJvm` (e.g. the `#GRADLE_LOCAL_JAVA_HOME` macro). No-op if gradle.xml is
     * absent (a fresh checkout: the project SDK we set covers the default `#USE_PROJECT_JDK`).
     */
    fun configureGradleJvm(jdkName: String) {
        val gradleXml = "$projectGuestDir/.idea/gradle.xml"
        val sed = "s|name=\"gradleJvm\" value=\"[^\"]*\"|name=\"gradleJvm\" value=\"$jdkName\"|"
        driver.startProcessInContainer {
            this
                .args("bash", "-c", "f='$gradleXml'; if [ -f \"\$f\" ]; then sed -i '$sed' \"\$f\"; echo patched; else echo 'no gradle.xml'; fi")
                .timeoutSeconds(10)
                .description("Pin gradleJvm=$jdkName in $gradleXml")
                .quietly()
        }.assertExitCode(0) { "Failed to pin gradleJvm in $gradleXml: $stderr" }
        driver.log("Configured Gradle JVM=$jdkName for project")
    }

    /**
     * Set the project JDK in `.idea/misc.xml` to a registered JDK name BEFORE the IDE starts, so
     * the project SDK is resolved at project-open (rather than only by the post-open
     * `mcpSetProjectSdk`). Patches ONLY the `ProjectRootManager` component's `project-jdk-name`
     * attribute (adding it when absent — our fixtures ship misc.xml without it). No-op if misc.xml
     * or the component is missing; the post-open `mcpSetProjectSdk` still covers those.
     */
    fun configureProjectJdk(jdkName: String) {
        val miscXml = "$projectGuestDir/.idea/misc.xml"
        // Project sources live on the container-local filesystem (not host-mounted), so read the
        // file out of the container, patch it with the XML API (jdom2), and write it back.
        val current = driver.startProcessInContainer {
            this
                .args("bash", "-c", "f='$miscXml'; [ -f \"\$f\" ] && cat \"\$f\" || true")
                .timeoutSeconds(10)
                .description("Read $miscXml")
                .quietly()
        }.assertExitCode(0) { "Failed to read $miscXml: $stderr" }.stdout
        if (current.isBlank()) {
            driver.log("No misc.xml — skipping project JDK config (mcpSetProjectSdk covers it post-open)")
            return
        }

        // Patch ONLY the ProjectRootManager component's project-jdk-name (add when absent).
        val doc = SAXBuilder().build(StringReader(current))
        val projectRootManager = doc.rootElement.children.firstOrNull {
            it.name == "component" && it.getAttributeValue("name") == "ProjectRootManager"
        }
        if (projectRootManager == null) {
            driver.log("misc.xml has no ProjectRootManager component — skipping project JDK config")
            return
        }
        projectRootManager.setAttribute("project-jdk-name", jdkName)

        val patched = XMLOutputter(Format.getPrettyFormat().setLineSeparator("\n")).outputString(doc)
        driver.writeFileInContainer(miscXml, patched)
        driver.log("Configured project JDK=$jdkName in misc.xml")
    }

    fun deployPluginToContainer(pluginZipPath: File) {
        driver.deployZipAndUnpack(pluginZipPath, pluginsGuestDir)
    }
}
