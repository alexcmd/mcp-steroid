/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import java.io.PrintStream

class NpxKtArgs(args: Array<String>) {
    val args = args.toList()

    fun parseDebugFlag() = parseDebugFlag(args.toTypedArray())

    fun parseCliMode() = parseCliMode(args.toTypedArray())

    override fun toString(): String {
        return "NpxKtArgs(${args.joinToString(" ")})"
    }
}


/** Brand presented in CLI banners — see [BRAND_TAGLINE] for the full slogan. */
const val BRAND_NAME: String = "devrig"

/** Tagline used by the help banner and the `backend` subcommand's header. */
const val BRAND_TAGLINE: String =
    "This environment empowers your AI with the best deterministic coding tools."

const val NO_BACKENDS_DETECTED_MESSAGE: String = "No backends detected."

/**
 * What the user asked the launcher to do. Resolved by [parseCliMode] BEFORE any
 * other code runs — the MCP path must redirect `System.out` to stderr before
 * any logger / class loader has a chance to print to it, so we keep this
 * function pure and dependency-free.
 */
sealed interface CliMode {
    /** `mpc` was passed. Run as the stdio MCP server. */
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
 * Normalised argv shape used by [parseCliMode]. Flags keep their leading dash;
 * boolean flags have a `null` value, value flags store the parsed value.
 * [positionals] are the non-flag tokens after [mode], excluding [subcommand].
 */
data class ParsedArgs(
    val mode: String?,
    val subcommand: String?,
    val positionals: List<String>,
    val flags: Map<String, String?>,
    val unknownFlags: List<String>,
    val rawArgs: List<String>,
)

private data class ParseFailure(
    val args: List<String>,
    val hint: String,
)

private data class ModeRule(
    val mode: String?,
    val subcommand: String?,
    val expectedPositionals: IntRange,
    val allowedFlags: Set<String>,
    val valueFlags: Set<String> = emptySet(),
    val build: (ParsedArgs) -> CliMode,
)

private val globalBooleanFlags = setOf("--debug")
private val helpFlags = setOf("--help", "-h")
private val versionSelectorFlags = setOf("--version", "-v")
private val informationOverrideFlags = helpFlags + versionSelectorFlags
private val knownModeKeywords = setOf("mpc", "backend", "project")
private val backendLifecycleSubcommands = setOf("download", "start", "stop")
private val backendSubcommands = backendLifecycleSubcommands + "provision"
private val knownFlags = globalBooleanFlags + helpFlags + versionSelectorFlags + setOf("--json", "--allow-paid")

private val cliModeRules: List<ModeRule> = listOf(
    ModeRule(
        mode = null,
        subcommand = "help",
        expectedPositionals = 0..0,
        allowedFlags = helpFlags,
    ) { CliMode.Help },
    ModeRule(
        mode = null,
        subcommand = "version",
        expectedPositionals = 0..0,
        allowedFlags = versionSelectorFlags,
    ) { CliMode.Version },
    ModeRule(
        mode = "mpc",
        subcommand = null,
        expectedPositionals = 0..0,
        allowedFlags = emptySet(),
    ) { CliMode.Mcp },
    ModeRule(
        mode = "backend",
        subcommand = "download",
        expectedPositionals = 0..1,
        allowedFlags = setOf("--json"),
        valueFlags = setOf("--version"),
    ) { parsed ->
        val json = parsed.hasFlag("--json")
        val id = parsed.positionals.firstOrNull()
            ?: return@ModeRule CliMode.Backend.DownloadList(json = json)
        if (!isSupportedBackendLifecycleId(id)) {
            return@ModeRule CliMode.Unknown(
                args = listOf("backend", "download", id),
                hint = "Run `devrig backend download` with no id to list valid backend ids.",
            )
        }
        CliMode.Backend.Download(
            id = id,
            versionOverride = parsed.flags["--version"],
            json = json,
        )
    },
    ModeRule(
        mode = "backend",
        subcommand = "start",
        expectedPositionals = 0..1,
        allowedFlags = setOf("--json"),
        valueFlags = setOf("--version"),
    ) { parsed ->
        val json = parsed.hasFlag("--json")
        val id = parsed.positionals.firstOrNull()
            ?: return@ModeRule CliMode.Backend.StartList(json = json)
        if (!isSupportedBackendLifecycleId(id)) {
            return@ModeRule CliMode.Unknown(
                args = listOf("backend", "start", id),
                hint = "Run `devrig backend start` with no id to list valid backend ids.",
            )
        }
        CliMode.Backend.Start(
            id = id,
            versionOverride = parsed.flags["--version"],
            json = json,
        )
    },
    ModeRule(
        mode = "backend",
        subcommand = "stop",
        expectedPositionals = 0..1,
        allowedFlags = setOf("--json"),
        valueFlags = setOf("--version"),
    ) { parsed ->
        val json = parsed.hasFlag("--json")
        val id = parsed.positionals.firstOrNull()
            ?: return@ModeRule CliMode.Backend.StopList(json = json)
        if (!isSupportedBackendLifecycleId(id)) {
            return@ModeRule CliMode.Unknown(
                args = listOf("backend", "stop", id),
                hint = "Run `devrig backend stop` with no id to list valid backend ids.",
            )
        }
        CliMode.Backend.Stop(
            id = id,
            versionOverride = parsed.flags["--version"],
            json = json,
        )
    },
    ModeRule(
        mode = "backend",
        subcommand = "provision",
        expectedPositionals = 0..1,
        allowedFlags = setOf("--json"),
    ) { parsed ->
        val json = parsed.hasFlag("--json")
        val id = parsed.positionals.firstOrNull()
            ?: return@ModeRule CliMode.Backend.ProvisionList(json = json)
        if (!isSupportedProvisionTargetId(id)) {
            return@ModeRule CliMode.Unknown(
                args = listOf("backend", "provision", id),
                hint = "Run `devrig backend provision` with no id to list valid backend ids.",
            )
        }
        CliMode.Backend.Provision(id = id, json = json)
    },
    ModeRule(
        mode = "backend",
        subcommand = null,
        expectedPositionals = 0..0,
        allowedFlags = setOf("--json"),
    ) { parsed ->
        if (parsed.hasFlag("--json")) CliMode.Backend.Json else CliMode.Backend.Text
    },
    ModeRule(
        mode = "project",
        subcommand = null,
        expectedPositionals = 0..0,
        allowedFlags = setOf("--json"),
    ) { parsed ->
        if (parsed.hasFlag("--json")) CliMode.Project.Json else CliMode.Project.Text
    },
)

/**
 * Pure arg parser. Touches nothing but its input — safe to call before
 * stdout redirection or any class init.
 *
 * Precedence (highest first): `--help` / `-h` / empty → `--version` / `-v` →
 * `mpc` → `backend` → `project` → Unknown. The info selectors intentionally
 * win over data subcommands so `backend --help` prints usage instead of
 * opening connections; non-info modes are strict and reject unknown flags,
 * missing value-flag values, and extra positional arguments.
 *
 * `--debug` is **orthogonal** to the mode (it toggles log verbosity, see
 * [parseDebugFlag]) and is accepted in every mode.
 */
fun parseCliMode(args: Array<String>): CliMode {
    val rawArgs = args.toList()
    if (rawArgs.any { it in helpFlags }) return CliMode.Help
    if (rawArgs.hasVersionSelector()) return CliMode.Version

    val modeIndex = findModeIndex(rawArgs)
    val mode = modeIndex?.let { rawArgs[it] }
    val subcommand = if (mode == "backend") findBackendSubcommand(rawArgs, modeIndex) else null
    val backendModeIndex = modeIndex ?: -1
    val versionValueAllowedAt: (Int) -> Boolean = { index ->
        mode == "backend" && subcommand in backendLifecycleSubcommands && index > backendModeIndex
    }
    parseValueFlagFailure(rawArgs, "--version", valueAllowedAt = versionValueAllowedAt)?.let {
        return CliMode.Unknown(args = it.args, hint = it.hint)
    }

    val parsed = parseArgs(rawArgs, modeIndex, mode, subcommand, versionValueAllowedAt)
    removedAllowPaidFailure(parsed)?.let { return it }

    val rule = selectModeRule(parsed)
        ?: return unknownTopLevel(parsed)

    validateRule(parsed, rule)?.let { return it }
    return rule.build(parsed)
}

/**
 * `--debug` toggles verbose stderr logging (DEBUG instead of INFO). Pure and
 * orthogonal to [parseCliMode] — `--debug` is valid in EVERY mode, including
 * `mpc` where it still goes to stderr (stdout stays reserved for NDJSON).
 */
fun parseDebugFlag(args: Array<String>): Boolean = args.any { it == "--debug" }

private fun ParsedArgs.hasFlag(flag: String): Boolean = flags.containsKey(flag)

private fun List<String>.hasVersionSelector(): Boolean {
    var index = 0
    while (index < size) {
        when (this[index]) {
            "-v" -> return true
            "--version" -> {
                val value = getOrNull(index + 1)
                if (value == null || value.startsWith("-") || value in knownModeKeywords) return true
                index += 2
            }
            else -> index++
        }
    }
    return false
}

private fun parseValueFlagFailure(
    args: List<String>,
    flag: String,
    valueAllowedAt: (Int) -> Boolean,
): ParseFailure? {
    args.forEachIndexed { index, arg ->
        if (arg == flag && valueAllowedAt(index)) {
            val value = args.getOrNull(index + 1)
            if (value == null || value.startsWith("--")) {
                return ParseFailure(
                    args = listOfNotNull(flag, value),
                    hint = "Missing value for $flag",
                )
            }
        }
    }
    return null
}

private fun findModeIndex(args: List<String>): Int? {
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg.startsWith("-") -> index++
            arg in knownModeKeywords -> return index
            else -> return null
        }
    }
    return null
}

private fun findBackendSubcommand(args: List<String>, backendIndex: Int): String? {
    var index = backendIndex + 1
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "--version" && index < args.lastIndex && !args[index + 1].startsWith("--") -> index += 2
            arg.startsWith("-") -> index++
            arg in backendSubcommands -> return arg
            else -> return null
        }
    }
    return null
}

private fun parseArgs(
    args: List<String>,
    modeIndex: Int?,
    mode: String?,
    subcommand: String?,
    versionValueAllowedAt: (Int) -> Boolean,
): ParsedArgs {
    val positionals = mutableListOf<String>()
    val flags = linkedMapOf<String, String?>()
    val unknownFlags = mutableListOf<String>()
    var subcommandConsumed = false
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "--version" && versionValueAllowedAt(index) -> {
                index = consumeOptionalFlagValue(args, index, flags)
            }
            arg.startsWith("-") -> {
                flags[arg] = null
                if (arg !in knownFlags) unknownFlags += arg
                index++
            }
            index == modeIndex -> {
                index++
            }
            mode == "backend" && subcommand != null && !subcommandConsumed && arg == subcommand && modeIndex != null && index > modeIndex -> {
                subcommandConsumed = true
                index++
            }
            modeIndex == null || index > modeIndex -> {
                positionals += arg
                index++
            }
            else -> {
                positionals += arg
                index++
            }
        }
    }
    return ParsedArgs(
        mode = mode,
        subcommand = subcommand,
        positionals = positionals,
        flags = flags,
        unknownFlags = unknownFlags,
        rawArgs = args,
    )
}

private fun consumeOptionalFlagValue(
    args: List<String>,
    index: Int,
    flags: MutableMap<String, String?>,
): Int {
    val flag = args[index]
    val value = args.getOrNull(index + 1)
    return if (value != null && !value.startsWith("--")) {
        flags[flag] = value
        index + 2
    } else {
        flags[flag] = null
        index + 1
    }
}

private fun selectModeRule(parsed: ParsedArgs): ModeRule? {
    if (parsed.mode == null) {
        if (parsed.rawArgs.isEmpty() || parsed.onlyGlobalFlagsAndValues()) {
            return cliModeRules.first { it.mode == null && it.subcommand == "help" }
        }
        val hasHelp = parsed.flags.keys.any { it in helpFlags }
        if (hasHelp) return cliModeRules.first { it.mode == null && it.subcommand == "help" }
        val hasVersion = parsed.flags.keys.any { it in versionSelectorFlags }
        if (hasVersion) return cliModeRules.first { it.mode == null && it.subcommand == "version" }
        return null
    }
    return cliModeRules.firstOrNull { it.mode == parsed.mode && it.subcommand == parsed.subcommand }
}

private fun ParsedArgs.onlyGlobalFlagsAndValues(): Boolean {
    return positionals.isEmpty() && flags.keys.all { it in globalBooleanFlags }
}

private fun removedAllowPaidFailure(parsed: ParsedArgs): CliMode.Unknown? {
    if (!parsed.flags.containsKey("--allow-paid")) return null
    return CliMode.Unknown(
        args = listOf("--allow-paid"),
        hint = "The --allow-paid flag was removed; requested JetBrains binaries are downloaded without a CLI consent flag.",
    )
}

private fun validateRule(parsed: ParsedArgs, rule: ModeRule): CliMode.Unknown? {
    parsed.unknownFlags.firstOrNull()?.let { return unknownFlag(it) }
    val allowedFlags = globalBooleanFlags + informationOverrideFlags + rule.allowedFlags + rule.valueFlags
    parsed.flags.keys.firstOrNull { it !in allowedFlags }?.let { return unknownFlag(it) }
    if (parsed.positionals.size !in rule.expectedPositionals) {
        val firstExtraIndex = rule.expectedPositionals.last
        val remainder = parsed.positionals.drop(firstExtraIndex).ifEmpty { parsed.positionals }
        val extra = remainder.first()
        return CliMode.Unknown(
            args = remainder,
            hint = "Unexpected extra argument: $extra",
        )
    }
    return null
}

private fun unknownFlag(flag: String): CliMode.Unknown = CliMode.Unknown(
    args = listOf(flag),
    hint = "Unknown flag: $flag",
)

private fun unknownTopLevel(parsed: ParsedArgs): CliMode.Unknown {
    parsed.unknownFlags.firstOrNull()?.let { return unknownFlag(it) }
    parsed.flags.keys.firstOrNull { it !in globalBooleanFlags }?.let { return unknownFlag(it) }
    return CliMode.Unknown(parsed.rawArgs)
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


/**
 * Runs the non-MCP CLI surface (help / version / backend / project / unknown).
 * Returns the process exit code the caller should propagate.
 *
 * `System.out` here is the real stdout because the MCP redirect only fires
 * on the `mpc` branch — that's the whole point of the early split in
 * [main]. Help and version go to stdout (standard CLI convention); the
 * error variant goes to stderr.
 */
fun runCli(
    mode: CliMode,
    homePaths: HomePaths,
): Int = when (mode) {
    CliMode.Mcp -> error("runCli called with CliMode.Mcp — caller should branch to mainImpl instead")
    CliMode.Help -> {
        printHelp(System.out)
        0
    }
    CliMode.Version -> {
        println(ProxyVersionMetadata.getProxyVersion())
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
                is CliMode.Backend.Provision -> runBackendProvisionCommand(System.out, mode)
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
    val version = ProxyVersionMetadata.getProxyVersion()
    out.print(
        """
        $BRAND_NAME v$version — $BRAND_TAGLINE

        Usage:
          devrig mpc                           run as an MCP stdio server
                                               (stdin / stdout reserved for the MCP transport)
          devrig backend [--json]              list discovered backends (with versions) and the
                                               projects each one has open. `--json` emits a
                                               single machine-readable object on stdout
                                               (pipe through `jq`); default is human text.
          devrig project [--json]              list open projects across discovered backends.
                                               `--json` emits a single machine-readable
                                               object on stdout; default is human text.
          devrig backend download [<id>] [--version <v>] [--json]
                                               no id → list IDEs available for download.
                                               With id, download and install a managed
                                               backend under the devrig home. Accepts
                                               <product>, <product>:<version>, or
                                               <product>-<version>.
          devrig backend start    [<id>] [--version <v>] [--json]
                                               no id → list installed backends. With id,
                                               start an installed managed backend in
                                               detached mode and print its pid/log/config
                                               paths. Product-only id prefers the
                                               highest locally installed backend.
          devrig backend stop     [<id>] [--version <v>] [--json]
                                               no id → list currently running backends.
                                               With id, stop a managed backend by pid file.
                                               Product-only id prefers the highest
                                               locally installed backend.
          devrig backend provision [<id>] [--json]
                                               no id → list port-discovered IDEs that can be
                                               provisioned. With id (for example port-63342),
                                               print manual MCP Steroid plugin install
                                               instructions for that IDE.
          devrig --version | -v                print the proxy version and exit
          devrig --help    | -h                print this help and exit

        Options applicable to every mode:
          --debug                              enable verbose stderr logging (DEBUG)
                                               — without it, INFO+ are shown.

        Unknown flags or extra arguments are rejected with exit code 64.
        Run with --debug for verbose logging.

        The MCP mode is intentionally opt-in: the launcher behaves like a regular CLI by
        default so it can be inspected (`--help`, `--version`, `backend`, `project`) without
        consuming stdin or committing stdout to the JSON-RPC framing convention.
        """.trimIndent() + "\n"
    )
}
