/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

const val NO_BACKENDS_DETECTED_MESSAGE: String = "No backends detected."

class NpxKtArgs(
    args: Array<String>,
    private val parent: NpxKtArgs? = null,
) {
    val args: List<String> = args.toList()

    fun parseDebugFlag(): Boolean = option("--debug")
    fun jsonFlag(): Boolean = option("--json")
    fun helpFlag(): Boolean = option("--help") || option("-h")

    fun command(): NpxKtCommand? {
        val command = firstCommandToken()
        if (command == null) {
            if (args.isEmpty() || onlyGlobalFlags()) return NpxKtCommand.NpxCommandHelp
            if (option("--version") || option("-v")) return NpxKtCommand.NpxCommandVersion
            if (helpFlag()) return NpxKtCommand.NpxCommandHelp
            return null
        }

        return when (command.value) {
            "mpc" -> NpxKtCommand.MCP(argsAfter(command.index))
            "backend" -> backendCommand(argsAfter(command.index))
            "project" -> NpxKtCommand.NpxCommandProject(argsAfter(command.index))
            "help" -> NpxKtCommand.NpxCommandHelp
            "version" -> NpxKtCommand.NpxCommandVersion
            else -> null
        }
    }

    private fun backendCommand(restArgs: NpxKtArgs): NpxKtCommand {
        val subcommand = restArgs.firstCommandToken()
        return when (subcommand?.value) {
            "download" -> NpxKtCommand.NpxCommandBackendDownload(restArgs.argsAfter(subcommand.index))
            "start" -> NpxKtCommand.NpxCommandBackendStart(restArgs.argsAfter(subcommand.index))
            "stop" -> NpxKtCommand.NpxCommandBackendStop(restArgs.argsAfter(subcommand.index))
            "provision" -> NpxKtCommand.NpxCommandBackendProvision(restArgs.argsAfter(subcommand.index))
            else -> NpxKtCommand.NpxCommandBackend(restArgs)
        }
    }

    private fun firstCommandToken(): IndexedValue<String>? =
        args.withIndex().firstOrNull { (_, token) -> !token.startsWith("-") }

    fun argsAfter(index: Int): NpxKtArgs =
        NpxKtArgs(args.drop(index + 1).toTypedArray(), parent = this)

    fun option(option: String): Boolean {
        require(option.startsWith("-"))
        return args.any { it == option } || parent?.option(option) == true
    }

    fun optionValue(option: String): OptionValue {
        require(option.startsWith("-"))
        val local = optionValueLocal(option)
        if (local != OptionValue.Absent) return local
        return parent?.optionValue(option) ?: OptionValue.Absent
    }

    private fun optionValueLocal(option: String): OptionValue {
        val index = args.indexOf(option)
        if (index < 0) return OptionValue.Absent
        val value = args.getOrNull(index + 1)
        if (value == null || value.startsWith("-")) return OptionValue.Missing(option)
        return OptionValue.Present(value)
    }

    fun positionals(): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            when {
                token == "--version" -> index += if (args.getOrNull(index + 1)?.startsWith("-") == false) 2 else 1
                token.startsWith("-") -> index++
                else -> {
                    result += token
                    index++
                }
            }
        }
        return result
    }

    fun unknownOptions(allowed: Set<String>): List<String> {
        val unknown = mutableListOf<String>()
        collectUnknownOptions(allowed, unknown)
        return unknown.distinct()
    }

    private fun collectUnknownOptions(allowed: Set<String>, output: MutableList<String>) {
        for (token in args) {
            if (token.startsWith("-") && token !in allowed) output += token
        }
        parent?.collectUnknownOptions(allowed, output)
    }

    private fun onlyGlobalFlags(): Boolean =
        args.all { it == "--debug" }

    override fun toString(): String {
        val parentText = parent?.let { ", parent=$it" }.orEmpty()
        return "NpxKtArgs(${args.joinToString(" ")}$parentText)"
    }
}

sealed interface OptionValue {
    data object Absent : OptionValue
    data class Missing(val option: String) : OptionValue
    data class Present(val value: String) : OptionValue
}

sealed interface NpxKtCommand {
    val restArgs: NpxKtArgs

    data class MCP(override val restArgs: NpxKtArgs) : NpxKtCommand
    data class NpxCommandBackend(override val restArgs: NpxKtArgs) : NpxKtCommand
    data class NpxCommandBackendDownload(override val restArgs: NpxKtArgs) : NpxKtCommand
    data class NpxCommandBackendStart(override val restArgs: NpxKtArgs) : NpxKtCommand
    data class NpxCommandBackendStop(override val restArgs: NpxKtArgs) : NpxKtCommand
    data class NpxCommandBackendProvision(override val restArgs: NpxKtArgs) : NpxKtCommand
    data class NpxCommandProject(override val restArgs: NpxKtArgs) : NpxKtCommand

    data object NpxCommandHelp : NpxKtCommand {
        override val restArgs: NpxKtArgs = NpxKtArgs(emptyArray())
    }

    data object NpxCommandVersion : NpxKtCommand {
        override val restArgs: NpxKtArgs = NpxKtArgs(emptyArray())
    }
}

suspend fun NpxKtServices.runCli(command: NpxKtCommand?): Int {
    if (command != null && command !is NpxKtCommand.MCP && command.restArgs.helpFlag()) {
        return printHelp(System.out)
    }
    return try {
        when (command) {
            is NpxKtCommand.MCP -> error("runCli called with NpxKtCommand.MCP")
            NpxKtCommand.NpxCommandHelp -> printHelp(System.out)
            NpxKtCommand.NpxCommandVersion -> printVersion(System.out)
            null -> unknownArguments(args.args)
            is NpxKtCommand.NpxCommandBackend -> runBackendCommand(System.out, homePaths, command)
            is NpxKtCommand.NpxCommandBackendDownload -> runBackendDownloadCommand(System.out, homePaths, command)
            is NpxKtCommand.NpxCommandBackendStart -> runBackendStartCommand(System.out, homePaths, command)
            is NpxKtCommand.NpxCommandBackendStop -> runBackendStopCommand(System.out, homePaths, command)
            is NpxKtCommand.NpxCommandBackendProvision -> runBackendProvisionCommand(System.out, command)
            is NpxKtCommand.NpxCommandProject -> runProjectCommand(System.out, command)
        }
    } catch (e: ManagedBackendLockException) {
        System.err.println(e.message)
        64
    } catch (e: ManagedBackendValidationException) {
        System.err.println(e.message)
        64
    }
}

fun unknownArguments(tokens: List<String>, hint: String? = null): Int {
    System.err.println("Unknown argument(s): ${tokens.joinToString(" ")}")
    hint?.let { System.err.println(it) }
    printHelp(System.err)
    return 64
}
