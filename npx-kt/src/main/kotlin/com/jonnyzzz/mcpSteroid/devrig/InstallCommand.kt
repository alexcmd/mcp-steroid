/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliResult
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliRunner
import com.jonnyzzz.mcpSteroid.aiAgents.ProcessAiAgentCliRunner
import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.mcpAddStdioInvocation
import com.jonnyzzz.mcpSteroid.aiAgents.mcpRemoveInvocation
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.isRegularFile

private const val DEVRIG_MCP_SERVER_NAME = "mcp-steroid"

fun DevrigServices.runInstallCommand(
    command: DevrigCommand.DevrigCommandInstall,
    runner: AiAgentCliRunner = ProcessAiAgentCliRunner(),
): Int = runInstallCommand(
    command = command,
    launcher = resolveDevrigLauncher(),
    javaHome = Path.of(System.getProperty("java.home")),
    out = mcpStdout,
    err = System.err,
    runner = runner,
)

/**
 * Registers devrig as the `mcp-steroid` stdio MCP server in [command]'s agent, narrating each step so
 * the user understands exactly what is being changed.
 *
 * The registration is an **idempotent upsert**: it first removes any existing `mcp-steroid` entry, then
 * adds the current one. That is what makes re-running `devrig install` safe — it repairs a stale entry
 * (old launcher path or subcommand) instead of failing with "already exists", which is the trap users
 * hit when an earlier version or a different bootstrapper had registered the server (see issues #84/#86).
 */
fun runInstallCommand(
    command: DevrigCommand.DevrigCommandInstall,
    launcher: Path,
    javaHome: Path,
    out: PrintStream,
    err: PrintStream,
    runner: AiAgentCliRunner,
): Int {
    val agent = command.agent
    val mcpCommand = selfMcpCommand(launcher, javaHome)
    val renderedCommand = "${mcpCommand.command} ${mcpCommand.args.joinToString(" ")}"

    out.println("Installing devrig as the '$DEVRIG_MCP_SERVER_NAME' MCP server for ${agent.displayName}.")
    out.println()
    out.println("What this does:")
    out.println("  - Registers a stdio MCP server named '$DEVRIG_MCP_SERVER_NAME' in ${agent.displayName} (user scope).")
    out.println("  - ${agent.displayName} will launch this command to start it:")
    out.println("      $renderedCommand")
    out.println("  - JAVA_HOME recorded for that launch: $javaHome")
    out.println()
    out.println("Re-running install is safe — any previous '$DEVRIG_MCP_SERVER_NAME' entry is replaced.")
    out.println()

    // Step 1 — remove any prior registration. This is best-effort: the agent CLIs exit non-zero when
    // the server is not present, which is the normal first-install case, so a non-zero here does NOT
    // fail the install. A genuine removal failure surfaces immediately afterwards as an "already exists"
    // error from the add step, which IS reported. The underlying agent message goes to stderr either way.
    out.println("Step 1/2: removing any existing '$DEVRIG_MCP_SERVER_NAME' registration…")
    val removeResult = runner.run(mcpRemoveInvocation(agent, DEVRIG_MCP_SERVER_NAME))
    if (removeResult.exitCode == 0) {
        out.println("  removed a previous registration.")
    } else {
        out.println("  no existing registration to remove (expected on a first install).")
    }
    emitAgentOutput(removeResult, err)

    // Step 2 — add the current registration.
    out.println("Step 2/2: registering '$DEVRIG_MCP_SERVER_NAME' with ${agent.displayName}…")
    val addInvocation = mcpAddStdioInvocation(agent, mcpCommand, DEVRIG_MCP_SERVER_NAME)
    val addResult = runner.run(addInvocation)
    emitAgentOutput(addResult, err)
    if (addResult.exitCode != 0) {
        err.println()
        err.println(
            "Registration FAILED: '${agent.binary} ${addInvocation.args.joinToString(" ")}' " +
                "exited with code ${addResult.exitCode}.",
        )
        err.println("Fix the error reported above, then re-run 'devrig install ${agent.binary}'.")
        return addResult.exitCode
    }

    out.println()
    out.println("Done — '$DEVRIG_MCP_SERVER_NAME' is registered for ${agent.displayName}.")
    out.println("  Command ${agent.displayName} will run: $renderedCommand")
    out.println("  Verify with: ${agent.binary} mcp list")
    return 0
}

private fun emitAgentOutput(result: AiAgentCliResult, err: PrintStream) {
    if (result.output.isNotBlank()) {
        err.print(result.output)
        if (!result.output.endsWith("\n")) err.println()
    }
}

fun selfMcpCommand(
    launcher: Path,
    javaHome: Path,
    windows: Boolean = isWindows(),
): StdioMcpCommand {
    val normalizedLauncher = launcher.toAbsolutePath().normalize()
    val normalizedJavaHome = javaHome.toAbsolutePath().normalize()
    return if (windows) {
        StdioMcpCommand(
            command = "cmd.exe",
            args = listOf("/d", "/c", "set \"JAVA_HOME=$normalizedJavaHome\" && call \"$normalizedLauncher\" mcp"),
        )
    } else {
        StdioMcpCommand(
            command = "/usr/bin/env",
            args = listOf("JAVA_HOME=$normalizedJavaHome", normalizedLauncher.toString(), "mcp"),
        )
    }
}

private fun resolveDevrigLauncher(): Path {
    val name = if (isWindows()) "devrig.bat" else "devrig"
    val expected = DevrigRoot.path.resolve("bin").resolve(name)
    if (expected.isRegularFile()) return expected

    // Fat-jar / non-Gradle-distribution layouts don't have `<root>/bin/devrig`.
    // Fall back to the current process's launcher so `devrig install`
    // still records a reproducible command line.
    val processCommand = ProcessHandle.current().info().command().orElse(null)
    if (processCommand != null) {
        val launcher = Path.of(processCommand)
        if (launcher.isRegularFile()) return launcher
    }

    error("devrig launcher is missing: $expected")
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").lowercase().contains("windows")
