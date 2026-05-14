/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * What the user asked the launcher to do. Resolved by [parseCliMode] BEFORE any
 * other code runs — the MCP path must redirect `System.out` to stderr before
 * any logger / class loader has a chance to print to it, so we keep this
 * function pure and dependency-free.
 */
internal sealed interface CliMode {
    /** `--mcp` was passed. Run as the stdio MCP server. */
    object Mcp : CliMode

    /** `--help`, `-h`, or no args — print usage on stdout and exit 0. */
    object Help : CliMode

    /** `--version` or `-v` — print the proxy version on stdout and exit 0. */
    object Version : CliMode

    /**
     * `backend` subcommand — print the set of discovered IDEs (version + open
     * projects) on stdout and exit 0. One-shot snapshot, no streaming.
     */
    object Backend : CliMode

    /** Unknown arg(s). Print usage on stderr and exit non-zero. */
    data class Unknown(val args: List<String>) : CliMode
}

/**
 * Pure arg parser. Touches nothing but its input — safe to call before
 * stdout redirection or any class init.
 *
 * Precedence (highest first): `--mcp` → `--help` / `-h` / empty → `--version`
 * / `-v` → `backend` → Unknown. The "info" modes (help, version) intentionally
 * win over `backend` so `backend --help` prints help instead of opening
 * connections, and `--mcp` wins over everything so a wrapper that accidentally
 * combines flags still keeps MCP framing intact.
 */
internal fun parseCliMode(args: Array<String>): CliMode {
    if (args.any { it == "--mcp" }) return CliMode.Mcp
    if (args.isEmpty() || args.any { it == "--help" || it == "-h" }) return CliMode.Help
    if (args.any { it == "--version" || it == "-v" }) return CliMode.Version
    if (args.any { it == "backend" }) return CliMode.Backend
    return CliMode.Unknown(args.toList())
}

/**
 * Runs the non-MCP CLI surface (help / version / unknown). Returns the
 * process exit code the caller should propagate.
 *
 * `System.out` here is the real stdout because the MCP redirect only fires
 * on the `--mcp` branch — that's the whole point of the early split in
 * [main]. Help and version go to stdout (standard CLI convention); the
 * error variant goes to stderr.
 */
internal fun runCli(mode: CliMode): Int = when (mode) {
    CliMode.Mcp -> error("runCli called with CliMode.Mcp — caller should branch to mainImpl instead")
    CliMode.Help -> {
        printHelp(System.out)
        0
    }
    CliMode.Version -> {
        System.out.println(loadProxyVersion())
        0
    }
    CliMode.Backend -> {
        runBackendCommand(System.out)
        0
    }
    is CliMode.Unknown -> {
        System.err.println("Unknown argument(s): ${mode.args.joinToString(" ")}")
        printHelp(System.err)
        64
    }
}

private fun printHelp(out: PrintStream) {
    out.print(
        """
        mcp-steroid-proxy — MCP-Steroid stdio proxy + IDE monitor.

        Usage:
          mcp-steroid-proxy --mcp           run as an MCP stdio server
                                            (stdin / stdout reserved for the MCP transport)
          mcp-steroid-proxy backend         list discovered IDEs (with versions) and
                                            the projects each one has open
          mcp-steroid-proxy --version | -v  print the proxy version and exit
          mcp-steroid-proxy --help    | -h  print this help and exit

        The MCP mode is intentionally opt-in: the launcher behaves like a regular CLI by
        default so it can be inspected (`--help`, `--version`, `backend`) without consuming
        stdin or committing stdout to the JSON-RPC framing convention.
        """.trimIndent() + "\n"
    )
}

/**
 * Exit-process wrapper for `runCli`. Kept separate so unit tests can exercise
 * [runCli] without killing the JVM.
 */
internal fun runCliAndExit(mode: CliMode): Nothing {
    exitProcess(runCli(mode))
}
