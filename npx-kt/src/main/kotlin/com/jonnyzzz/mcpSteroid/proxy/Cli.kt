/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import java.io.PrintStream
import kotlin.system.exitProcess

/** Brand presented in CLI banners — see [BRAND_TAGLINE] for the full slogan. */
internal const val BRAND_NAME: String = "devrig"

/** Tagline used by the help banner and the `backend` subcommand's header. */
internal const val BRAND_TAGLINE: String =
    "This environment empowers your AI with the best deterministic coding tools."

internal const val NO_BACKENDS_DETECTED_MESSAGE: String = "No backends detected."

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
        data class DownloadList(val json: Boolean) : Backend
        data class StartList(val json: Boolean) : Backend
        data class StopList(val json: Boolean) : Backend
        data class ProvisionList(val json: Boolean) : Backend
        data class Download(
            val id: String,
            val versionOverride: String?,
            val json: Boolean = false,
        ) : Backend
        data class Start(
            val id: String,
            val versionOverride: String?,
            val json: Boolean = false,
        ) : Backend
        data class Stop(
            val id: String,
            val versionOverride: String?,
            val json: Boolean = false,
        ) : Backend
        data class Provision(
            val id: String,
            val json: Boolean = false,
        ) : Backend
    }

    /**
     * `project` subcommand — print every open project across discovered IDEs
     * that have the mcp-steroid plugin installed. One-shot snapshot, no
     * streaming.
     *
     * Output format mirrors [Backend]: omitted `--json` ⇒ [Text], present
     * `--json` ⇒ [Json].
     */
    sealed interface Project : CliMode {
        data object Text : Project
        data object Json : Project
    }

    /** Unknown arg(s). Print usage on stderr and exit non-zero. */
    data class Unknown(val args: List<String>, val hint: String? = null) : CliMode
}

/**
 * Pure arg parser. Touches nothing but its input — safe to call before
 * stdout redirection or any class init.
 *
 * Precedence (highest first): `--mcp` → `--help` / `-h` / empty → `--version`
 * / `-v` → `backend` → `project` → Unknown. The "info" modes (help, version)
 * intentionally win over data subcommands so `backend --help` prints help
 * instead of opening connections, and `--mcp` wins over everything so a wrapper
 * that accidentally combines flags still keeps MCP framing intact.
 *
 * `--debug` is **orthogonal** to the mode (it toggles log verbosity, see
 * [parseDebugFlag]) and is filtered out here so e.g. `--debug` alone routes
 * to Help, not Unknown, and `--debug backend` routes to Backend.
 */
internal fun parseCliMode(args: Array<String>): CliMode {
    // `--debug` is a logging-verbosity toggle; `--json` is a backend-only output
    // format selector. `--home <path>` is a storage-root override. All three
    // are orthogonal to mode selection and filtered out here so e.g. `--debug`
    // alone routes to Help, not Unknown.
    val modeArgs = args.withoutGlobalValueFlag("--home")
        .filterNot { it == "--debug" || it == "--json" }
        .toTypedArray()
    if (modeArgs.any { it == "--mcp" }) return CliMode.Mcp
    if (modeArgs.isEmpty() || modeArgs.any { it == "--help" || it == "-h" }) return CliMode.Help
    if (modeArgs.any { it == "--allow-paid" }) {
        return CliMode.Unknown(
            args = listOf("--allow-paid"),
            hint = "The --allow-paid flag was removed; requested JetBrains binaries are downloaded without a CLI consent flag.",
        )
    }
    if (modeArgs.any { it == "backend" }) {
        parseBackendLifecycleMode(
            args.withoutGlobalValueFlag("--home")
                .filterNot { it == "--debug" }
                .toTypedArray(),
        )?.let { return it }
    }
    if (modeArgs.any { it == "--version" || it == "-v" }) return CliMode.Version
    if (modeArgs.any { it == "backend" }) {
        return if (args.any { it == "--json" }) CliMode.Backend.Json else CliMode.Backend.Text
    }
    if (modeArgs.any { it == "project" }) {
        return if (args.any { it == "--json" }) CliMode.Project.Json else CliMode.Project.Text
    }
    return CliMode.Unknown(modeArgs.toList())
}

/**
 * `--debug` toggles verbose stderr logging (DEBUG instead of WARN). Pure and
 * orthogonal to [parseCliMode] — `--debug` is valid in EVERY mode, including
 * `--mcp` where it still goes to stderr (stdout stays reserved for NDJSON).
 */
internal fun parseDebugFlag(args: Array<String>): Boolean = args.any { it == "--debug" }

internal fun parseHomeOverride(args: Array<String>): String? {
    val idx = args.indexOf("--home")
    if (idx < 0 || idx == args.lastIndex) return null
    return args[idx + 1]
}

private fun Array<String>.withoutGlobalValueFlag(flag: String): List<String> {
    return toList().withoutValueFlag(flag)
}

private fun List<String>.withoutValueFlag(flag: String): List<String> {
    val result = mutableListOf<String>()
    var skipNext = false
    for (arg in this) {
        if (skipNext) {
            skipNext = false
            continue
        }
        if (arg == flag) {
            skipNext = true
            continue
        }
        result += arg
    }
    return result
}

private fun parseBackendLifecycleMode(args: Array<String>): CliMode? {
    val resolutionArgs = args.toList()
        .withoutValueFlag("--version")
        .filterNot { it == "--json" }
        .toTypedArray()
    val backendIndex = resolutionArgs.indexOf("backend")
    if (backendIndex < 0 || backendIndex == resolutionArgs.lastIndex) return null
    val subcommand = resolutionArgs.getOrNull(backendIndex + 1) ?: return null
    if (subcommand !in setOf("download", "start", "stop", "provision")) return null
    val json = args.any { it == "--json" }
    val id = resolutionArgs.getOrNull(backendIndex + 2)
        ?: return when (subcommand) {
            "download" -> CliMode.Backend.DownloadList(json = json)
            "start" -> CliMode.Backend.StartList(json = json)
            "stop" -> CliMode.Backend.StopList(json = json)
            "provision" -> CliMode.Backend.ProvisionList(json = json)
            else -> CliMode.Unknown(listOf("backend", subcommand, "<missing-id>"))
        }
    if (id.startsWith("--")) {
        return when (subcommand) {
            "download" -> CliMode.Backend.DownloadList(json = json)
            "start" -> CliMode.Backend.StartList(json = json)
            "stop" -> CliMode.Backend.StopList(json = json)
            "provision" -> CliMode.Backend.ProvisionList(json = json)
            else -> CliMode.Unknown(listOf("backend", subcommand, "<missing-id>"))
        }
    }
    if (subcommand == "provision") {
        return if (isSupportedProvisionTargetId(id)) {
            CliMode.Backend.Provision(id = id, json = json)
        } else {
            CliMode.Unknown(
                args = listOf("backend", subcommand, id),
                hint = "Run `devrig backend provision` with no id to list valid backend ids.",
            )
        }
    }
    if (!isSupportedBackendLifecycleId(id)) {
        return CliMode.Unknown(
            args = listOf("backend", subcommand, id),
            hint = "Run `devrig backend $subcommand` with no id to list valid backend ids.",
        )
    }
    val versionOverride = valueAfter(args, "--version")
    return when (subcommand) {
        "download" -> CliMode.Backend.Download(
            id = id,
            versionOverride = versionOverride,
            json = json,
        )
        "start" -> CliMode.Backend.Start(
            id = id,
            versionOverride = versionOverride,
            json = json,
        )
        "stop" -> CliMode.Backend.Stop(
            id = id,
            versionOverride = versionOverride,
            json = json,
        )
        else -> null
    }
}

private fun isSupportedBackendLifecycleId(raw: String): Boolean {
    if (raw.isBlank()) return false
    val colonParts = raw.split(':')
    if (colonParts.size > 2) return false
    if (colonParts.size == 2) {
        return isKnownProductKey(colonParts[0]) && isSupportedBackendVersion(colonParts[1])
    }
    if (isKnownProductKey(raw)) return true
    val product = com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct.knownProducts
        .sortedByDescending { it.id.length }
        .firstOrNull { raw.startsWith("${it.id}-") }
        ?: return false
    return isSupportedBackendVersion(raw.removePrefix("${product.id}-"))
}

private fun isKnownProductKey(raw: String): Boolean =
    com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct.knownProducts.any { it.id == raw }

private fun isSupportedProvisionTargetId(raw: String): Boolean = Regex("""port-\d{1,5}""").matches(raw)

private fun valueAfter(args: Array<String>, flag: String): String? {
    val idx = args.indexOf(flag)
    if (idx < 0 || idx == args.lastIndex) return null
    return args[idx + 1]
}

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
 * Runs the non-MCP CLI surface (help / version / backend / project / unknown).
 * Returns the process exit code the caller should propagate.
 *
 * `System.out` here is the real stdout because the MCP redirect only fires
 * on the `--mcp` branch — that's the whole point of the early split in
 * [main]. Help and version go to stdout (standard CLI convention); the
 * error variant goes to stderr.
 */
internal fun runCli(
    mode: CliMode,
    homePaths: HomePaths = resolveHomePaths(override = null),
): Int = when (mode) {
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
        try {
            when (mode) {
                CliMode.Backend.Text -> {
                    runBackendCommand(System.out, json = false, homePaths = homePaths)
                    0
                }
                CliMode.Backend.Json -> {
                    runBackendCommand(System.out, json = true, homePaths = homePaths)
                    0
                }
                is CliMode.Backend.DownloadList -> {
                    runBackendDownloadListCommand(System.out, json = mode.json)
                    0
                }
                is CliMode.Backend.StartList -> {
                    runBackendStartListCommand(System.out, homePaths, json = mode.json)
                    0
                }
                is CliMode.Backend.StopList -> {
                    runBackendStopListCommand(System.out, homePaths, json = mode.json)
                    0
                }
                is CliMode.Backend.ProvisionList -> {
                    runBackendProvisionListCommand(System.out, json = mode.json)
                    0
                }
                is CliMode.Backend.Download -> runBackendDownloadCommand(System.out, homePaths, mode)
                is CliMode.Backend.Start -> runBackendStartCommand(System.out, homePaths, mode)
                is CliMode.Backend.Stop -> runBackendStopCommand(System.out, homePaths, mode)
                is CliMode.Backend.Provision -> runBackendProvisionCommand(System.out, homePaths, mode)
            }
        } catch (e: ManagedBackendLockException) {
            System.err.println(e.message)
            64
        } catch (e: ManagedBackendValidationException) {
            System.err.println(e.message)
            64
        }
    }
    is CliMode.Project -> {
        runProjectCommand(System.out, json = mode is CliMode.Project.Json)
        0
    }
    is CliMode.Unknown -> {
        System.err.println("Unknown argument(s): ${mode.args.joinToString(" ")}")
        mode.hint?.let { System.err.println(it) }
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
          mcp-steroid-proxy backend [--json]         list discovered backends (with versions) and the
                                                     projects each one has open. `--json` emits a
                                                     single machine-readable object on stdout
                                                     (pipe through `jq`); default is human text.
          mcp-steroid-proxy project [--json]         list open projects across discovered backends.
                                                     `--json` emits a single machine-readable
                                                     object on stdout; default is human text.
          mcp-steroid-proxy backend download [<id>] [--version <v>] [--json]
                                                     no id → list IDEs available for download.
                                                     With id, download and install a managed
                                                     backend under the devrig home. Accepts
                                                     <product>, <product>:<version>, or
                                                     <product>-<version>.
          mcp-steroid-proxy backend start    [<id>] [--json]
                                                     no id → list installed backends. With id,
                                                     start an installed managed backend in
                                                     detached mode and print its pid/log/config
                                                     paths. Product-only id prefers the
                                                     highest locally installed backend.
          mcp-steroid-proxy backend stop     [<id>] [--json]
                                                     no id → list currently running backends.
                                                     With id, stop a managed backend by pid file.
                                                     Product-only id prefers the highest
                                                     locally installed backend.
          mcp-steroid-proxy backend provision [<id>] [--json]
                                                     no id → list port-discovered IDEs that can be
                                                     provisioned. With id (for example port-63342),
                                                     install the bundled MCP Steroid plugin into
                                                     that IDE's user plugins directory.
          mcp-steroid-proxy --version | -v           print the proxy version and exit
          mcp-steroid-proxy --help    | -h           print this help and exit

        Options applicable to every mode:
          --home <path>                              store devrig logs, managed backends, caches,
                                                     and state under <path> instead of
                                                     ${'$'}MCP_STEROID_HOME / ~/.mcp-steroid
          --debug                                    enable verbose stderr logging (DEBUG)
                                                     — without it, only WARN+ are shown.

        The MCP mode is intentionally opt-in: the launcher behaves like a regular CLI by
        default so it can be inspected (`--help`, `--version`, `backend`, `project`) without
        consuming stdin or committing stdout to the JSON-RPC framing convention.
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
