/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.ConsoleAwareAgentSession
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigContainer
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.streamDevrigLogsToConsole
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.git.BareRepoCache
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * End-to-end console scenario for the **devrig managed-backend self-install** flow an external agent hits:
 * no pre-provisioned IDE — the agent uses devrig to fetch and start one, then opens a real project and
 * answers a code-intelligence question. Each step is visible in the on-video console.
 *
 * Flow:
 * 1. Clone Keycloak from the local bare-repo cache (`file://`, fast — no network fetch).
 * 2. `devrig install claude` — register devrig's `mpc` stdio server as the `mcp-steroid` MCP for Claude.
 * 3. Run Claude with a find-usages task. Claude runs `devrig backend download/start idea-community` itself
 *    (via Bash), the already-running `devrig mpc` discovers the started backend's marker, and Claude drives
 *    the `mcp-steroid` tools to open Keycloak and find usages of a chosen symbol.
 *
 * Heaviest test in the suite: full IDE download + Keycloak clone + indexing + an agent turn. The managed
 * IDEA archive is pre-staged from the host cache for speed (see [stageCachedIdeaArchive]).
 *
 * ```
 * ./gradlew :test-integration:test --tests '*DevrigManagedBackendAgentE2ETest*'
 * ```
 */
class DevrigManagedBackendAgentE2ETest {

    private val projectDirInContainer = "/home/agent/keycloak"

    // DEVRIG_HOME stays container-only (fast, and keeps the multi-GB downloaded IDE out of the run-dir
    // artifacts). Only its small `logs` dir is bind-mounted to the host (devrigLogsHostMountGuestPath
    // below), so the JVM-side monitor (streamDevrigLogsToConsole) can tail the DEBUG log files directly,
    // the same way the IntelliJ idea.log is pumped.
    private val devrigHome = "/tmp/mcp-home"
    private val devrigLogsGuestPath = "/tmp/mcp-home/logs"

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `claude uses devrig to provision an IDE and find usages in keycloak`() = runWithCloseableStack { lifetime ->
        // The cached bare repo lets the in-container clone be a local file:// copy instead of a full fetch.
        BareRepoCache.ensureRepo("https://github.com/keycloak/keycloak.git", IdeTestFolders.repoCacheDir)

        val container = DevrigContainer.create(
            lifetime,
            DevrigContainerOpts(
                consoleTitle = "managed-backend-agent-e2e",
                mountRepoCache = true,
                devrigLogsHostMountGuestPath = devrigLogsGuestPath,
            ),
        )
        val console = container.console
        // Lead with the full module — classname hierarchy so console/video output is attributable.
        console.writeHeader("test-integration — ${this::class.simpleName}")
        console.writeHeader("Managed-backend agent E2E (devrig self-install → find usages)")

        console.writeStep("Clone Keycloak from the local bare-repo cache")
        val clonedFromCache = GitDriver(container.scope).cloneFromCachedBare("keycloak/keycloak", projectDirInContainer)
        assertTrue(clonedFromCache) {
            "Keycloak must be cloneable from the mounted /repo-cache bare repo (warm the cache on the host first)."
        }
        container.execAndAssert(
            description = "confirm Keycloak checkout on disk",
            script = """
                set -euo pipefail
                test -d "$projectDirInContainer/.git"
                test -f "$projectDirInContainer/pom.xml"
            """.trimIndent(),
        )
        console.writeSuccess("Keycloak checked out at $projectDirInContainer")

        stageCachedIdeaArchive(container)

        console.writeStep("devrig install claude (register devrig mpc as the mcp-steroid MCP server)")
        container.execAndAssertWithConsoleStream(
            description = "devrig install claude",
            timeoutSeconds = 120,
            script = """
                set -euo pipefail
                "${container.devrig}" install claude
                claude mcp list
            """.trimIndent(),
        )

        // Tee devrig's own DEBUG log to the console so its activity (download, backend start, mpc bridge)
        // is visible live — the agent runs devrig via Claude's Bash, which buffers a command's stdout
        // until it finishes, so the log file is the only live window into what devrig is doing.
        streamDevrigLogsToConsole(lifetime, File(container.runDir, "devrig-logs"), console)

        console.writeStep("Run Claude: provision an IDE with devrig, open Keycloak, find usages")
        val claude = ConsoleAwareAgentSession(
            delegate = DockerClaudeSession.create(container.scope),
            console = console,
            agentName = "claude",
            logDir = container.runDir,
        )
        val result = claude.runPrompt(buildPrompt(container.devrig), timeoutSeconds = 45 * 60).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        // The agent must have driven the whole flow through devrig + the mcp-steroid tools.
        assertTrue(combined.contains("backend") && combined.contains("idea-community")) {
            "Agent must run `devrig backend download/start idea-community`. Output:\n${combined.take(6000)}"
        }
        assertTrue(combined.contains("steroid_open_project")) {
            "Agent must open the Keycloak project through the mcp-steroid tools. Output:\n${combined.take(6000)}"
        }
        assertTrue(combined.contains("steroid_execute_code")) {
            "Agent must run code in the managed IDE to find usages. Output:\n${combined.take(6000)}"
        }
        assertTrue(hasMarker(output, "DEVRIG_IDE_STARTED", "yes")) {
            "Missing DEVRIG_IDE_STARTED marker — the agent did not bring up the managed backend. Output:\n${output.take(6000)}"
        }
        assertTrue(hasMarker(output, "DEVRIG_PROJECT_OPENED", "yes")) {
            "Missing DEVRIG_PROJECT_OPENED marker. Output:\n${output.take(6000)}"
        }
        val usages = usageCount(output)
        assertTrue(usages != null && usages >= 1) {
            "Expected DEVRIG_USAGES: <n>= 1 (the agent found usages of the target symbol in Keycloak). " +
                "Parsed=$usages. Output:\n${output.take(6000)}"
        }

        // The managed backend must have actually started and advertised its MCP Steroid marker.
        container.execAndAssert(
            description = "confirm a managed-backend MCP Steroid marker exists",
            script = """
                set -euo pipefail
                ls /home/agent/.mcp-steroid/markers/*.mcp-steroid >/dev/null
            """.trimIndent(),
        )
        console.writeSuccess("Agent provisioned the IDE via devrig and reported $usages usage(s)")
    }

    private fun buildPrompt(devrigPath: String): String = buildString {
        appendLine("# Task: provision an IDE with devrig, then find usages in Keycloak")
        appendLine()
        appendLine("There is NO IDE running yet. The `devrig` CLI launcher is at: $devrigPath")
        appendLine("The `mcp-steroid` MCP server (backed by devrig) is already registered for you.")
        appendLine("The Keycloak project is checked out at $projectDirInContainer.")
        appendLine()
        appendLine("Do EXACTLY these steps, in order:")
        appendLine()
        appendLine("1. Download and start a managed IntelliJ IDEA backend with devrig (this can take a few")
        appendLine("   minutes). Run these two Bash commands (verbatim):")
        appendLine("     DEVRIG_HOME=$devrigHome \"$devrigPath\" backend download idea-community")
        appendLine("     DEVRIG_HOME=$devrigHome \"$devrigPath\" backend start idea-community")
        appendLine()
        appendLine("2. Call steroid_list_projects every ~10s until the started IDE appears (up to 3 minutes — the")
        appendLine("   IDE needs time to boot and connect). Pick the discovered project_name.")
        appendLine("3. Call steroid_open_project with project_path=$projectDirInContainer to open Keycloak.")
        appendLine("4. Call steroid_execute_code (project_name from step 2) to find usages of the interface")
        appendLine("   `org.keycloak.models.UserModel`. Use IntelliJ PSI — wait for indexing first, then:")
        appendLine("     - JavaPsiFacade.getInstance(project).findClass(\"org.keycloak.models.UserModel\", allScope)")
        appendLine("     - com.intellij.psi.search.searches.ReferencesSearch.search(psiClass).findAll().size")
        appendLine("   Print the usage count.")
        appendLine("5. Reply with EXACTLY these marker lines and nothing else after them:")
        appendLine("DEVRIG_IDE_STARTED: yes")
        appendLine("DEVRIG_PROJECT_OPENED: yes")
        appendLine("DEVRIG_USAGES: <the number of usages you found>")
    }

    /**
     * Pre-copy a cached `ideaIC-*.tar.gz` from the host archive cache into the container's devrig downloads
     * dir, so the agent's `devrig backend download` reuses it instead of fetching ~1 GB over the network.
     * Best-effort: if no cached archive exists, the agent's download still works (just slower).
     */
    private fun stageCachedIdeaArchive(container: DevrigContainer) {
        val cacheDir = (System.getenv("MCP_STEROID_TEST_ARCHIVE_CACHE")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".cache/mcp-steroid-test")).absoluteFile
        val archive = try {
            cacheDir.listFiles { f -> f.isFile && Regex("""ideaIC-.*\.tar\.gz""").matches(f.name) }
                ?.minByOrNull { it.name }
        } catch (e: Exception) {
            container.console.writeInfo("WARN: cannot read archive cache ${cacheDir.absolutePath}: ${e.message}")
            null
        }
        if (archive == null) {
            container.console.writeInfo("No cached ideaIC archive in ${cacheDir.absolutePath}; agent download will fetch from network")
            return
        }
        container.execAndAssert(
            description = "prepare devrig downloads dir",
            // Don't `rm -rf $devrigHome` — its `logs` subdir is a bind mount (busy). Just ensure downloads.
            script = "set -euo pipefail; mkdir -p $devrigHome/downloads",
        )
        container.scope.copyToContainer(archive, "$devrigHome/downloads/${archive.name}")
        container.console.writeInfo("Staged cached IDE archive: ${archive.name}")
    }

    private fun hasMarker(output: String, marker: String, expected: String): Boolean =
        output.lineSequence().any { it.contains("$marker: $expected", ignoreCase = true) }

    private fun usageCount(output: String): Int? =
        output.lineSequence()
            .mapNotNull { Regex("""DEVRIG_USAGES:\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .lastOrNull()
}
