/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliInvocation
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliResult
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliRunner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class InstallCommandTest {
    private val launcher = Path.of("/opt/devrig/bin/devrig")
    private val javaHome = Path.of("/opt/jdk-21")

    @Test
    fun `self mcp command uses current java home on unix`() {
        val command = selfMcpCommand(launcher, javaHome, windows = false)

        assertEquals("/usr/bin/env", command.command)
        assertEquals(listOf("JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mcp"), command.args)
    }

    @Test
    fun `self mcp command uses cmd exe for bat launchers on windows`() {
        val command = selfMcpCommand(Path.of("/opt/devrig/bin/devrig.bat"), Path.of("/opt/jdk-21"), windows = true)

        assertEquals("cmd.exe", command.command)
        assertEquals(
            listOf("/d", "/c", "set \"JAVA_HOME=/opt/jdk-21\" && call \"/opt/devrig/bin/devrig.bat\" mcp"),
            command.args,
        )
    }

    @Test
    fun `install upserts claude — removes any prior registration, then adds`() {
        val result = runInstallFor(AiAgentCli.CLAUDE)

        assertEquals(0, result.exitCode)
        // Idempotency is implemented as remove-then-add, in that order.
        assertEquals(2, result.invocations.size, "expected a remove followed by an add; got ${result.invocations}")

        val remove = result.invocations[0]
        assertEquals("claude", remove.binary)
        assertEquals(listOf("mcp", "remove", "--scope", "user", "mcp-steroid"), remove.args)

        val add = result.invocations[1]
        assertEquals("claude", add.binary)
        assertEquals(
            listOf("mcp", "add", "--scope", "user", "mcp-steroid", "--",
                "/usr/bin/env", "JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mcp"),
            add.args,
        )
    }

    @Test
    fun `install upserts codex — remove then add`() {
        val result = runInstallFor(AiAgentCli.CODEX)

        assertEquals(0, result.exitCode)
        assertEquals(listOf("mcp", "remove", "mcp-steroid"), result.invocations[0].args)
        assertEquals(
            listOf("mcp", "add", "mcp-steroid", "--", "/usr/bin/env", "JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mcp"),
            result.invocations[1].args,
        )
    }

    @Test
    fun `install upserts gemini — remove then add`() {
        val result = runInstallFor(AiAgentCli.GEMINI)

        assertEquals(0, result.exitCode)
        assertEquals(listOf("mcp", "remove", "--scope", "user", "mcp-steroid"), result.invocations[0].args)
        assertEquals(
            listOf(
                "mcp", "add", "--type", "stdio", "--scope", "user", "--trust", "mcp-steroid",
                "/usr/bin/env", "JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mcp",
            ),
            result.invocations[1].args,
        )
    }

    @Test
    fun `install output explains what it is doing, the launch command, and how to verify`() {
        val result = runInstallFor(AiAgentCli.CLAUDE)
        val out = result.stdout

        // It states the goal in plain language…
        assertContains(out, "mcp-steroid")
        assertContains(out, "Claude")
        // …shows the exact command the agent will launch…
        assertContains(out, "/usr/bin/env JAVA_HOME=/opt/jdk-21 /opt/devrig/bin/devrig mcp")
        // …records the JAVA_HOME…
        assertContains(out, "/opt/jdk-21")
        // …promises idempotency…
        assertTrue(out.contains("Re-running", ignoreCase = true) || out.contains("safe", ignoreCase = true), out)
        // …narrates the two steps…
        assertContains(out, "remov")
        assertContains(out, "regist")
        // …and tells the user how to confirm it worked.
        assertContains(out, "claude mcp list")
    }

    @Test
    fun `install is idempotent — first install with nothing to remove still succeeds`() {
        // claude/codex exit non-zero from `mcp remove` when the server is not present. That is the
        // normal first-install case and must NOT fail the overall install.
        val result = runInstall(
            AiAgentCli.CLAUDE,
            removeResult = AiAgentCliResult(exitCode = 1, output = "No MCP server mcp-steroid found\n"),
            addResult = AiAgentCliResult(exitCode = 0, output = "Added stdio MCP server mcp-steroid\n"),
        )

        assertEquals(0, result.exitCode)
        assertTrue(
            result.stdout.contains("no existing", ignoreCase = true) ||
                result.stdout.contains("nothing to remove", ignoreCase = true),
            "first install should explain there was nothing to remove; got:\n${result.stdout}",
        )
    }

    @Test
    fun `install reports a registration failure clearly and returns the agent exit code`() {
        val result = runInstall(
            AiAgentCli.CLAUDE,
            removeResult = AiAgentCliResult(exitCode = 0, output = "Removed\n"),
            addResult = AiAgentCliResult(exitCode = 17, output = "boom: could not write config\n"),
        )

        assertEquals(17, result.exitCode)
        // The underlying agent message is surfaced…
        assertContains(result.stderr, "boom: could not write config")
        // …and the failure is explained with the exit code.
        assertContains(result.stderr, "17")
        assertTrue(result.stderr.contains("fail", ignoreCase = true), result.stderr)
    }

    @Test
    fun `install does not print a registration failure when it succeeds`() {
        val result = runInstallFor(AiAgentCli.CLAUDE)
        assertFalse(result.stderr.contains("fail", ignoreCase = true), result.stderr)
    }

    private fun runInstallFor(agent: AiAgentCli): InstallRunResult =
        runInstall(
            agent,
            removeResult = AiAgentCliResult(exitCode = 0, output = "Removed previous registration\n"),
            addResult = AiAgentCliResult(exitCode = 0, output = "registered\n"),
        )

    private fun runInstall(
        agent: AiAgentCli,
        removeResult: AiAgentCliResult,
        addResult: AiAgentCliResult,
    ): InstallRunResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val runner = RecordingRunner(removeResult, addResult)
        val exitCode = runInstallCommand(
            command = DevrigCommand.DevrigCommandInstall(agent),
            launcher = launcher,
            javaHome = javaHome,
            out = PrintStream(stdout, true, Charsets.UTF_8),
            err = PrintStream(stderr, true, Charsets.UTF_8),
            runner = runner,
        )
        return InstallRunResult(
            exitCode = exitCode,
            invocations = runner.invocations,
            stdout = stdout.toString(Charsets.UTF_8),
            stderr = stderr.toString(Charsets.UTF_8),
        )
    }

    /** Captures every invocation and returns the remove/add result based on the subcommand. */
    private class RecordingRunner(
        private val removeResult: AiAgentCliResult,
        private val addResult: AiAgentCliResult,
    ) : AiAgentCliRunner {
        val invocations = mutableListOf<AiAgentCliInvocation>()
        override fun run(invocation: AiAgentCliInvocation): AiAgentCliResult {
            invocations += invocation
            return if (invocation.args.contains("remove")) removeResult else addResult
        }
    }

    private data class InstallRunResult(
        val exitCode: Int,
        val invocations: List<AiAgentCliInvocation>,
        val stdout: String,
        val stderr: String,
    )
}
