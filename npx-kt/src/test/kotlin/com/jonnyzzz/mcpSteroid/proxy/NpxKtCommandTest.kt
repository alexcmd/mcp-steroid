/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class NpxKtCommandTest {
    @Test
    fun `command selects top level commands`() {
        assertEquals(NpxKtCommand.NpxCommandHelp, command())
        assertEquals(NpxKtCommand.NpxCommandHelp, command("--debug"))
        assertEquals(NpxKtCommand.NpxCommandHelp, command("help"))
        assertEquals(NpxKtCommand.NpxCommandVersion, command("version"))
        assertEquals(NpxKtCommand.NpxCommandVersion, command("--version"))
        assertIs<NpxKtCommand.MCP>(command("mpc"))
        assertIs<NpxKtCommand.NpxCommandBackend>(command("backend"))
        assertIs<NpxKtCommand.NpxCommandProject>(command("project"))
    }

    @Test
    fun `command keeps command specific arguments on the subclass`() {
        val backend = assertIs<NpxKtCommand.NpxCommandBackend>(command("--json", "backend"))
        assertTrue(backend.restArgs.jsonFlag())

        val download = assertIs<NpxKtCommand.NpxCommandBackendDownload>(command("backend", "download", "idea-community", "--version", "2025.3", "--json"))
        assertEquals(listOf("idea-community"), download.restArgs.positionals())
        assertEquals(OptionValue.Present("2025.3"), download.restArgs.optionValue("--version"))
        assertTrue(download.restArgs.jsonFlag())

        val project = assertIs<NpxKtCommand.NpxCommandProject>(command("project", "--json"))
        assertTrue(project.restArgs.jsonFlag())
    }

    @Test
    fun `unknown command returns null`() {
        assertNull(command("foo"))
        assertNull(command("--no-such"))
    }

    @Test
    fun `removed home flag is not a command`() {
        assertNull(command("--home", "/tmp/devrig-home"))
    }

    private fun command(vararg args: String): NpxKtCommand? =
        NpxKtArgs(args.toList().toTypedArray()).command()
}
