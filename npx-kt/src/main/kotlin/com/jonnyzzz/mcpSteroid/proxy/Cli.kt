/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import java.io.PrintStream
import kotlin.system.exitProcess

/** Brand presented in CLI banners — see [BRAND_TAGLINE] for the full slogan. */
internal const val BRAND_NAME: String = "devrig"

/** Tagline used by the help banner and the `backend` subcommand's header. */
internal const val BRAND_TAGLINE: String =
    "the AI-empowered development environment for your project."

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
     *
     * Output format is selected by the `--json` flag: omitted ⇒ [Text] (human
     * banner + numbered list), present ⇒ [Json] (single object on stdout
     * suitable for `jq` / scripting). Sealed interface so callers can pattern-
     * match without losing the format-orthogonal "this is the backend mode"
     * signal.
     */
    sealed interface Backend : CliMode {
        data object Text : Backend
        data object Json : Backend
    }

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
 *
 * `--debug` is **orthogonal** to the mode (it toggles log verbosity, see
 * [parseDebugFlag]) and is filtered out here so e.g. `--debug` alone routes
 * to Help, not Unknown, and `--debug backend` routes to Backend.
 */
internal fun parseCliMode(args: Array<String>): CliMode {
    // `--debug` is a logging-verbosity toggle; `--json` is a backend-only output
    // format selector. Both are orthogonal to mode selection and filtered out
    // here so e.g. `--debug` alone routes to Help, not Unknown.
    val modeArgs = args.filterNot { it == "--debug" || it == "--json" }.toTypedArray()
    if (modeArgs.any { it == "--mcp" }) return CliMode.Mcp
    if (modeArgs.isEmpty() || modeArgs.any { it == "--help" || it == "-h" }) return CliMode.Help
    if (modeArgs.any { it == "--version" || it == "-v" }) return CliMode.Version
    if (modeArgs.any { it == "backend" }) {
        return if (args.any { it == "--json" }) CliMode.Backend.Json else CliMode.Backend.Text
    }
    return CliMode.Unknown(modeArgs.toList())
}

/**
 * `--debug` toggles verbose stderr logging (DEBUG instead of WARN). Pure and
 * orthogonal to [parseCliMode] — `--debug` is valid in EVERY mode, including
 * `--mcp` where it still goes to stderr (stdout stays reserved for NDJSON).
 */
internal fun parseDebugFlag(args: Array<String>): Boolean = args.any { it == "--debug" }

/**
 * Wire the `--debug` flag into the bundled logback configuration. Reads the
 * `proxy.log.level` system property at logback-init time (see `logback.xml`):
 *  - default: WARN
 *  - `--debug`: DEBUG
 *
 * MUST run before the first SLF4J call — logback initialises lazily on first
 * use and pins the level. [main] calls this right after [parseDebugFlag] for
 * exactly that reason.
 */
internal fun applyDebugLogging(debug: Boolean) {
    // Only set the property when --debug is requested — leaving it unset lets
    // operators override the WARN default from the outside with
    // `-Dproxy.log.level=INFO` etc. The hard-coded default in logback.xml
    // (`${proxy.log.level:-WARN}`) handles the no-flag case.
    if (debug) {
        System.setProperty("proxy.log.level", "DEBUG")
    }
}

/**
 * Runs the non-MCP CLI surface (help / version / backend / unknown). Returns
 * the process exit code the caller should propagate.
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
    is CliMode.Backend -> {
        runBackendCommand(System.out, json = mode is CliMode.Backend.Json)
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
        $BRAND_NAME v${loadProxyVersion()} — $BRAND_TAGLINE

        Usage:
          mcp-steroid-proxy --mcp                    run as an MCP stdio server
                                                     (stdin / stdout reserved for the MCP transport)
          mcp-steroid-proxy backend [--json]         list discovered IDEs (with versions) and the
                                                     projects each one has open. `--json` emits a
                                                     single machine-readable object on stdout
                                                     (pipe through `jq`); default is human text.
          mcp-steroid-proxy --version | -v           print the proxy version and exit
          mcp-steroid-proxy --help    | -h           print this help and exit

        Options applicable to every mode:
          --debug                                    enable verbose stderr logging (DEBUG)
                                                     — without it, only WARN+ are shown.

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
