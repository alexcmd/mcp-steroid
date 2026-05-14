/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks down the `--mcp` / CLI dispatch in [parseCliMode]. The contract this test
 * pins matters because the launcher's `main()` redirects `System.out` → stderr
 * the moment it sees `--mcp` and **only** then — any wrong classification here
 * would either silently break MCP framing (false-positive `Mcp`) or commit
 * stdout to NDJSON when the user just wanted `--help` (false-negative).
 */
class CliModeTest {

    @Test
    fun `empty argv routes to help`() {
        assertEquals(CliMode.Help, parseCliMode(emptyArray()))
    }

    @Test
    fun `--help and -h route to help`() {
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--help")))
        assertEquals(CliMode.Help, parseCliMode(arrayOf("-h")))
    }

    @Test
    fun `--version and -v route to version`() {
        assertEquals(CliMode.Version, parseCliMode(arrayOf("--version")))
        assertEquals(CliMode.Version, parseCliMode(arrayOf("-v")))
    }

    @Test
    fun `--mcp routes to mcp mode`() {
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp")))
    }

    @Test
    fun `--mcp wins over --help when both are present`() {
        // Defensive: if a wrapper script ever passes both flags, we'd rather
        // run the MCP stdio server than print help and exit, because the latter
        // means the MCP client process gets a closed pipe and a confusing
        // 'Method not found' on its initialize handshake.
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--help", "--mcp")))
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp", "--help")))
    }

    @Test
    fun `unknown args land in Unknown with the original token list`() {
        val mode = parseCliMode(arrayOf("--what", "--never"))
        assertTrue(mode is CliMode.Unknown, "expected Unknown, got $mode")
        assertEquals(listOf("--what", "--never"), (mode as CliMode.Unknown).args)
    }

    @Test
    fun `runCli help returns exit 0`() {
        // Smoke-test that runCli doesn't throw. We can't easily capture stdout in
        // a unit test without restructuring runCli's signature; the wiring test
        // above already verifies the dispatch decision, and the integration test
        // exercises the actual help text from a real subprocess.
        assertEquals(0, runCli(CliMode.Help))
        assertEquals(0, runCli(CliMode.Version))
        assertEquals(64, runCli(CliMode.Unknown(listOf("--no-such"))))
    }
}
