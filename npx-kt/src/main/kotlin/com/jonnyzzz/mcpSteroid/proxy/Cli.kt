/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple

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
        val path = NpxKtCommandSelector().select(args)
        if (path == null || path.isEmpty()) return rootCommand()
        return when (path) {
            listOf("mpc") -> NpxKtCommand.MCP(argsAfterCommandPath(path))
            listOf("backend") -> NpxKtCommand.NpxCommandBackend(argsAfterCommandPath(path))
            listOf("backend", "download") -> NpxKtCommand.NpxCommandBackendDownload(argsAfterCommandPath(path))
            listOf("backend", "start") -> NpxKtCommand.NpxCommandBackendStart(argsAfterCommandPath(path))
            listOf("backend", "stop") -> NpxKtCommand.NpxCommandBackendStop(argsAfterCommandPath(path))
            listOf("backend", "provision") -> NpxKtCommand.NpxCommandBackendProvision(argsAfterCommandPath(path))
            listOf("project") -> NpxKtCommand.NpxCommandProject(argsAfterCommandPath(path))
            listOf("help") -> NpxKtCommand.NpxCommandHelp
            listOf("version") -> NpxKtCommand.NpxCommandVersion
            else -> null
        }
    }

    private fun rootCommand(): NpxKtCommand? = when {
        args.isEmpty() || onlyGlobalFlags() -> NpxKtCommand.NpxCommandHelp
        option("--version") || option("-v") -> NpxKtCommand.NpxCommandVersion
        helpFlag() -> NpxKtCommand.NpxCommandHelp
        else -> null
    }

    private fun argsAfterCommandPath(path: List<String>): NpxKtArgs {
        var searchFrom = 0
        var lastIndex = -1
        for (command in path) {
            val index = args.withIndex()
                .firstOrNull { (index, token) -> index >= searchFrom && token == command }
                ?.index
                ?: return NpxKtArgs(emptyArray(), parent = this)
            lastIndex = index
            searchFrom = index + 1
        }
        return argsAfter(lastIndex)
    }

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

private class NpxKtCommandSelector {
    private var selectedPath: List<String>? = null

    fun select(args: List<String>): List<String>? {
        val command = RootCommand(this)
        try {
            command.parse(args.filterNot { it.startsWith("-") })
        } catch (_: CliktError) {
            return null
        }
        return selectedPath
    }

    private fun selectPath(path: List<String>) {
        selectedPath = path
    }

    private class RootCommand(
        private val selector: NpxKtCommandSelector,
    ) : CliktCommand(
        name = "devrig",
        invokeWithoutSubcommand = true,
        treatUnknownOptionsAsArgs = true,
    ) {
        init {
            context {
                allowInterspersedArgs = false
                helpOptionNames = emptySet<String>()
            }
            subcommands(
                MpcCommand(selector),
                BackendCommand(selector),
                ProjectCommand(selector),
                HelpCommand(selector),
                VersionCommand(selector),
            )
        }

        override fun run() {
            selector.selectPath(emptyList())
        }
    }

    private open class SelectingCommand(
        name: String,
        private val selector: NpxKtCommandSelector,
        private val path: List<String>,
    ) : CliktCommand(
        name = name,
        invokeWithoutSubcommand = true,
        treatUnknownOptionsAsArgs = true,
    ) {
        @Suppress("unused")
        private val rest: List<String> by argument().multiple(required = false)

        init {
            context {
                allowInterspersedArgs = false
                helpOptionNames = emptySet<String>()
            }
        }

        override fun run() {
            selector.selectPath(path)
        }
    }

    private class MpcCommand(selector: NpxKtCommandSelector) :
        SelectingCommand("mpc", selector, listOf("mpc"))

    private class ProjectCommand(selector: NpxKtCommandSelector) :
        SelectingCommand("project", selector, listOf("project"))

    private class HelpCommand(selector: NpxKtCommandSelector) :
        SelectingCommand("help", selector, listOf("help"))

    private class VersionCommand(selector: NpxKtCommandSelector) :
        SelectingCommand("version", selector, listOf("version"))

    private class BackendCommand(
        selector: NpxKtCommandSelector,
    ) : SelectingCommand("backend", selector, listOf("backend")) {
        init {
            subcommands(
                BackendDownloadCommand(selector),
                BackendStartCommand(selector),
                BackendStopCommand(selector),
                BackendProvisionCommand(selector),
            )
        }
    }

    private class BackendDownloadCommand(selector: NpxKtCommandSelector) :
        SelectingCommand("download", selector, listOf("backend", "download"))

    private class BackendStartCommand(selector: NpxKtCommandSelector) :
        SelectingCommand("start", selector, listOf("backend", "start"))

    private class BackendStopCommand(selector: NpxKtCommandSelector) :
        SelectingCommand("stop", selector, listOf("backend", "stop"))

    private class BackendProvisionCommand(selector: NpxKtCommandSelector) :
        SelectingCommand("provision", selector, listOf("backend", "provision"))
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
        return printHelp(mcpStdout)
    }
    return try {
        when (command) {
            is NpxKtCommand.MCP -> error("runCli called with NpxKtCommand.MCP")
            NpxKtCommand.NpxCommandHelp -> printHelp(mcpStdout)
            NpxKtCommand.NpxCommandVersion -> printVersion(mcpStdout)
            null -> unknownArguments(args.args)
            is NpxKtCommand.NpxCommandBackend -> runBackendCommand(mcpStdout, homePaths, command)
            is NpxKtCommand.NpxCommandBackendDownload -> runBackendDownloadCommand(mcpStdout, homePaths, command)
            is NpxKtCommand.NpxCommandBackendStart -> runBackendStartCommand(mcpStdout, homePaths, command)
            is NpxKtCommand.NpxCommandBackendStop -> runBackendStopCommand(mcpStdout, homePaths, command)
            is NpxKtCommand.NpxCommandBackendProvision -> runBackendProvisionCommand(mcpStdout, command)
            is NpxKtCommand.NpxCommandProject -> runProjectCommand(mcpStdout, command)
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
