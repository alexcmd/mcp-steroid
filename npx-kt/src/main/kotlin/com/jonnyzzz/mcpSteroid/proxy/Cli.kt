/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

const val NO_BACKENDS_DETECTED_MESSAGE: String = "No backends detected."

sealed interface NpxKtCommand {
    val debug: Boolean
    val json: Boolean

    data class MCP(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : NpxKtCommand

    data class NpxCommandBackend(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : NpxKtCommand

    data class NpxCommandBackendDownload(
        val id: String? = null,
        val version: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : NpxKtCommand

    data class NpxCommandBackendStart(
        val id: String? = null,
        val version: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : NpxKtCommand

    data class NpxCommandBackendStop(
        val id: String? = null,
        val version: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : NpxKtCommand

    data class NpxCommandBackendProvision(
        val id: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : NpxKtCommand

    data class NpxCommandProject(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : NpxKtCommand

    data class NpxCommandHelp(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : NpxKtCommand

    data class NpxCommandVersion(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : NpxKtCommand

    data class NpxCommandParseError(
        val text: String,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : NpxKtCommand
}

fun parseNpxKtCommand(rawArgs: Array<String>): NpxKtCommand {
    val selected = SelectedNpxKtCommand()
    val root = DevrigRootCommand(selected)
    return try {
        root.parse(rawArgs)
        selected.command ?: NpxKtCommand.NpxCommandHelp()
    } catch (e: CliktError) {
        NpxKtCommand.NpxCommandParseError(root.getFormattedHelp(e) ?: e.message ?: "Invalid arguments")
    }
}

private class SelectedNpxKtCommand {
    var command: NpxKtCommand? = null
}

private data class GenericOptions(
    val debug: Boolean,
    val json: Boolean,
    val help: Boolean,
)

private abstract class DevrigCommand(
    name: String,
    private val selected: SelectedNpxKtCommand,
    private val parent: DevrigCommand?,
    invokeWithoutSubcommand: Boolean = false,
) : CliktCommand(
    name = name,
    invokeWithoutSubcommand = invokeWithoutSubcommand,
) {
    private val debugFlag by option("--debug", help = "enable verbose stderr logging").flag()
    private val jsonFlag by option("--json", help = "emit JSON output where supported").flag()
    private val helpFlag by option("--help", "-h", help = "print help and exit").flag()

    init {
        context {
            helpOptionNames = emptySet()
        }
    }

    protected fun options(): GenericOptions {
        val parentOptions = parent?.options()
        return GenericOptions(
            debug = debugFlag || parentOptions?.debug == true,
            json = jsonFlag || parentOptions?.json == true,
            help = helpFlag || parentOptions?.help == true,
        )
    }

    protected fun select(command: NpxKtCommand) {
        val options = options()
        selected.command = if (options.help) {
            NpxKtCommand.NpxCommandHelp(debug = options.debug, json = options.json)
        } else {
            command
        }
    }
}

private class DevrigRootCommand(
    selected: SelectedNpxKtCommand,
) : DevrigCommand(
    name = "devrig",
    selected = selected,
    parent = null,
    invokeWithoutSubcommand = true,
) {
    private val versionFlag by option("--version", "-v", help = "print the proxy version and exit").flag()

    init {
        val backend = BackendCommand(selected, this)
        subcommands(
            MpcCommand(selected, this),
            backend,
            ProjectCommand(selected, this),
            HelpCommand(selected, this),
            VersionCommand(selected, this),
        )
    }

    override fun run() {
        val options = options()
        if (versionFlag) {
            select(NpxKtCommand.NpxCommandVersion(debug = options.debug, json = options.json))
        } else {
            select(NpxKtCommand.NpxCommandHelp(debug = options.debug, json = options.json))
        }
    }
}

private class MpcCommand(
    selected: SelectedNpxKtCommand,
    parent: DevrigCommand,
) : DevrigCommand("mpc", selected, parent) {
    override fun run() {
        val options = options()
        select(NpxKtCommand.MCP(debug = options.debug, json = options.json))
    }
}

private class ProjectCommand(
    selected: SelectedNpxKtCommand,
    parent: DevrigCommand,
) : DevrigCommand("project", selected, parent) {
    override fun run() {
        val options = options()
        select(NpxKtCommand.NpxCommandProject(debug = options.debug, json = options.json))
    }
}

private class HelpCommand(
    selected: SelectedNpxKtCommand,
    parent: DevrigCommand,
) : DevrigCommand("help", selected, parent) {
    override fun run() {
        val options = options()
        select(NpxKtCommand.NpxCommandHelp(debug = options.debug, json = options.json))
    }
}

private class VersionCommand(
    selected: SelectedNpxKtCommand,
    parent: DevrigCommand,
) : DevrigCommand("version", selected, parent) {
    override fun run() {
        val options = options()
        select(NpxKtCommand.NpxCommandVersion(debug = options.debug, json = options.json))
    }
}

private class BackendCommand(
    selected: SelectedNpxKtCommand,
    parent: DevrigCommand,
) : DevrigCommand("backend", selected, parent, invokeWithoutSubcommand = true) {
    init {
        subcommands(
            BackendDownloadCommand(selected, this),
            BackendStartCommand(selected, this),
            BackendStopCommand(selected, this),
            BackendProvisionCommand(selected, this),
        )
    }

    override fun run() {
        val options = options()
        select(NpxKtCommand.NpxCommandBackend(debug = options.debug, json = options.json))
    }
}

private class BackendDownloadCommand(
    selected: SelectedNpxKtCommand,
    parent: DevrigCommand,
) : DevrigCommand("download", selected, parent) {
    private val id by argument("id").optional()
    private val version by option("--version", help = "IDE version to download")

    override fun run() {
        val options = options()
        select(NpxKtCommand.NpxCommandBackendDownload(id = id, version = version, debug = options.debug, json = options.json))
    }
}

private class BackendStartCommand(
    selected: SelectedNpxKtCommand,
    parent: DevrigCommand,
) : DevrigCommand("start", selected, parent) {
    private val id by argument("id").optional()
    private val version by option("--version", help = "IDE version to start")

    override fun run() {
        val options = options()
        select(NpxKtCommand.NpxCommandBackendStart(id = id, version = version, debug = options.debug, json = options.json))
    }
}

private class BackendStopCommand(
    selected: SelectedNpxKtCommand,
    parent: DevrigCommand,
) : DevrigCommand("stop", selected, parent) {
    private val id by argument("id").optional()
    private val version by option("--version", help = "IDE version to stop")

    override fun run() {
        val options = options()
        select(NpxKtCommand.NpxCommandBackendStop(id = id, version = version, debug = options.debug, json = options.json))
    }
}

private class BackendProvisionCommand(
    selected: SelectedNpxKtCommand,
    parent: DevrigCommand,
) : DevrigCommand("provision", selected, parent) {
    private val id by argument("id").optional()

    override fun run() {
        val options = options()
        select(NpxKtCommand.NpxCommandBackendProvision(id = id, debug = options.debug, json = options.json))
    }
}

fun NpxKtServices.runCli(command: NpxKtCommand): Int {
    return try {
        when (command) {
            is NpxKtCommand.MCP -> error("runCli called with NpxKtCommand.MCP")
            is NpxKtCommand.NpxCommandHelp -> printHelp(mcpStdout)
            is NpxKtCommand.NpxCommandVersion -> printVersion(mcpStdout)
            is NpxKtCommand.NpxCommandParseError -> {
                System.err.println(command.text)
                64
            }
            is NpxKtCommand.NpxCommandBackend -> runBackendCommand(command)
            is NpxKtCommand.NpxCommandBackendDownload -> runBackendDownloadCommand(command)
            is NpxKtCommand.NpxCommandBackendStart -> runBackendStartCommand(command)
            is NpxKtCommand.NpxCommandBackendStop -> runBackendStopCommand(command)
            is NpxKtCommand.NpxCommandBackendProvision -> runBackendProvisionCommand(command)
            is NpxKtCommand.NpxCommandProject -> runProjectCommand(command)
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
