/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

class NpxKtArgs(
    args: Array<String>,
    private val parent: NpxKtArgs? = null
) {
    val args = args.toList()

    fun parseDebugFlag() = option("--debug")
    fun jsonFlag() : Boolean = option("--json")

    override fun toString(): String {
        val s = "NpxKtArgs(${args.joinToString(" ")})"
        if (parent != null) return s + parent.toString()
        return s
    }

    fun command() : NpxKtCommand? {
        val isMcp = arg("mcp")
        if (isMcp) return NpxKtCommand.MCP()

        argsSliceAfterPrefix("backend")?.let { restArgs ->
            restArgs.argsSliceAfterPrefix("start")?.let { restArgs ->
                return NpxKtCommand.NpxCommandBackendStart(restArgs)
            }

            restArgs.argsSliceAfterPrefix("stop")?.let { restArgs ->
                return NpxKtCommand.NpxCommandBackendStop(restArgs)
            }

            restArgs.argsSliceAfterPrefix("download")?.let { restArgs ->
                return NpxKtCommand.NpxCommandBackendDownload(restArgs)
            }

            restArgs.argsSliceAfterPrefix("provision")?.let { restArgs ->
                return NpxKtCommand.NpxCommandBackendProvision(restArgs)
            }

            return NpxKtCommand.NpxCommandBackendX(restArgs)
        }

        argsSliceAfterPrefix("project")?.let {
            return NpxKtCommand.NpxCommandProject(it)
        }

        val isVersion = arg("version") || arg("--version") || arg("-v")
        if (isVersion) return NpxKtCommand.NpxCommandVersion()

        val isHelp = arg("--help") || arg("-h") || arg("help")
        if (isHelp) return NpxKtCommand.NpxCommandHelp()

        return null
    }

    /**
     * Looks in the commandline for [cmdArg] and returns all arguments after it
     */
    fun argsSliceAfterPrefix(cmdArg: String): NpxKtArgs? {
        require(!cmdArg.startsWith("--"))

        val command = args.withIndex()
            .singleOrNull { it.value.equals(cmdArg, ignoreCase = true) }
            ?: return null

        val index = command.index
        //TODO: include all --args too
        return NpxKtArgs(args.subList(index + 1, args.size).toTypedArray(), parent = this)
    }

    fun arg(arg: String): Boolean {
        require(!arg.startsWith("--"))

        return args.any {
            it.equals(arg, ignoreCase = true)
        }
    }

    fun option(arg: String): Boolean {
        require(arg.startsWith("--"))

        return args.any {
            it.equals(arg, ignoreCase = true)
        } || parent?.option(arg) ?: false
    }

    fun arg(arg1: String, arg2: String): Boolean {
        require(!arg1.startsWith("--"))
        require(!arg2.startsWith("--"))

        for (i in args.indices) {
            if (i == args.lastIndex) continue
            if (!args[i].equals(arg1, ignoreCase = true)) continue
            if (!args[i+1].equals(arg2, ignoreCase = true)) continue
            return true
        }

        return false
    }

}

sealed interface NpxKtCommand {
    class MCP : NpxKtCommand

    class NpxCommandBackendX(val restArgs: NpxKtArgs): NpxKtCommand
    class NpxCommandBackendDownload(val restArgs: NpxKtArgs): NpxKtCommand
    class NpxCommandBackendStart(val restArgs: NpxKtArgs): NpxKtCommand
    class NpxCommandBackendStop(val restArgs: NpxKtArgs): NpxKtCommand
    class NpxCommandBackendProvision(val restArgs: NpxKtArgs): NpxKtCommand

    class NpxCommandProject(val restArgs: NpxKtArgs) : NpxKtCommand

    class NpxCommandHelp : NpxKtCommand
    class NpxCommandVersion : NpxKtCommand
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
suspend fun NpxKtServices.runCli(
    command: NpxKtCommand?,
    homePaths: HomePaths,
): Int = when (command) {
    is NpxKtCommand.MCP-> error("runCli called with NpxKtCommand.MCP")


    NpxKtCommand.NpxCommandHelp -> printHelp(System.out)
    NpxKtCommand.NpxCommandVersion -> printVersion(System.out)


    null -> {
        System.err.println("Unknown argument(s): ${args.args.joinToString(" ")}")
        printHelp(System.err)
        64
    }
    NpxKtCommand.NpxCommandBackend -> handleBackendCommandFamily(homePaths)
    NpxKtCommand.NpxCommandProject -> runProjectCommand(System.out, json = args.jsonFlag())
}

suspend fun NpxKtServices.handleBackendCommandFamily(): Int {
    try {
        val isDownload = args.arg("backend", "download")
        val isStart = args.arg("backend", "start")
        val isStop = args.arg("backend", "stop")
        val isProvision = args.arg("backend", "provision")

        val isJson = args.jsonFlag()

        when {
            isDownload -> runBackendDownloadListCommand(System.out, json = isJson)
            isStart -> runBackendStartListCommand(System.out, homePaths, json = isJson)
            isStop -> runBackendStopListCommand(System.out, homePaths, json = isJson)
            isProvision -> runBackendProvisionListCommand(System.out, json = isJson)

            else -> runBackendCommand(System.out, json = isJson, homePaths = homePaths)



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
