/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class NpxKtCommandTest {
    @Test
    fun `command selects top level commands`() {
        assertIs<NpxKtCommand.NpxCommandHelp>(command())
        assertTrue(assertIs<NpxKtCommand.NpxCommandHelp>(command("--debug")).debug)
        assertIs<NpxKtCommand.NpxCommandHelp>(command("help"))
        assertIs<NpxKtCommand.NpxCommandVersion>(command("version"))
        assertIs<NpxKtCommand.NpxCommandVersion>(command("--version"))
        assertIs<NpxKtCommand.MCP>(command("mpc"))
        assertIs<NpxKtCommand.NpxCommandBackend>(command("backend"))
        assertIs<NpxKtCommand.NpxCommandProject>(command("project"))
    }

    @Test
    fun `command keeps command specific arguments on the subclass`() {
        val backend = assertIs<NpxKtCommand.NpxCommandBackend>(command("--json", "backend"))
        assertTrue(backend.json)

        val download = assertIs<NpxKtCommand.NpxCommandBackendDownload>(command("backend", "download", "idea-community", "--version", "2025.3", "--json"))
        assertEquals("idea-community", download.id)
        assertEquals("2025.3", download.version)
        assertTrue(download.json)

        val interspersed = assertIs<NpxKtCommand.NpxCommandBackendDownload>(command("backend", "--json", "download", "idea-community"))
        assertEquals("idea-community", interspersed.id)
        assertTrue(interspersed.json)

        val project = assertIs<NpxKtCommand.NpxCommandProject>(command("project", "--json"))
        assertTrue(project.json)
    }

    @Test
    fun `generic switches are accepted at every command level`() {
        assertTrue(assertIs<NpxKtCommand.MCP>(command("--json", "mpc")).json)
        assertTrue(assertIs<NpxKtCommand.MCP>(command("mpc", "--json")).json)
        assertIs<NpxKtCommand.NpxCommandHelp>(command("mpc", "--help"))

        val download = assertIs<NpxKtCommand.NpxCommandBackendDownload>(
            command("--debug", "backend", "--json", "download", "idea-community"),
        )
        assertTrue(download.debug)
        assertTrue(download.json)
        assertEquals("idea-community", download.id)
    }

    @Test
    fun `root version and backend version stay scoped`() {
        assertIs<NpxKtCommand.NpxCommandVersion>(command("--version"))

        val download = assertIs<NpxKtCommand.NpxCommandBackendDownload>(
            command("backend", "download", "idea-community", "--version", "2025.3"),
        )
        assertEquals("2025.3", download.version)
    }

    @Test
    fun `unknown command returns parse error`() {
        assertIs<NpxKtCommand.NpxCommandParseError>(command("foo"))
        assertIs<NpxKtCommand.NpxCommandParseError>(command("--no-such"))
        assertIs<NpxKtCommand.NpxCommandParseError>(command("backend", "download", "idea-community", "extra"))
    }

    @Test
    fun `removed home flag is not a command`() {
        assertIs<NpxKtCommand.NpxCommandParseError>(command("--home", "/tmp/devrig-home"))
    }

    private fun command(vararg args: String): NpxKtCommand =
        parseNpxKtCommand(args.toList().toTypedArray())
}
