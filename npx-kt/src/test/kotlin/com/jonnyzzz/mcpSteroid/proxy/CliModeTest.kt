/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the contract of [parseCliMode]. The launcher's `main()` calls this as its
 * very first statement and redirects `System.out` → stderr the moment it sees
 * `CliMode.Mcp` — so any misclassification here either silently breaks MCP
 * framing (false-positive `Mcp`) or commits stdout to NDJSON when the user just
 * wanted `--help` (false-negative). Cover edges, not just happy paths.
 */
class CliModeTest {

    // ----------------------------- happy paths -----------------------------

    @Test
    fun `empty argv routes to help`() {
        assertEquals(CliMode.Help, parseCliMode(emptyArray()))
    }

    @Test
    fun `--help routes to help`() {
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--help")))
    }

    @Test
    fun `-h short form routes to help`() {
        assertEquals(CliMode.Help, parseCliMode(arrayOf("-h")))
    }

    @Test
    fun `--version routes to version`() {
        assertEquals(CliMode.Version, parseCliMode(arrayOf("--version")))
    }

    @Test
    fun `-v short form routes to version`() {
        assertEquals(CliMode.Version, parseCliMode(arrayOf("-v")))
    }

    @Test
    fun `--mcp routes to mcp mode`() {
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp")))
    }

    // ----------------------------- precedence ------------------------------

    @Test
    fun `--mcp wins over --help when both are present`() {
        // Defensive: a wrapper script that accidentally passes both flags should
        // still run the MCP server, not print help and exit. Closing the pipe on
        // an MCP client mid-handshake is the strictly worse failure mode.
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--help", "--mcp")))
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp", "--help")))
    }

    @Test
    fun `--mcp wins over --version`() {
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--version", "--mcp")))
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp", "-v")))
    }

    @Test
    fun `--mcp wins over unknown args`() {
        // Forward-compatibility: a future `--config foo` flag should not strand
        // older binaries in CLI mode; presence of `--mcp` is enough.
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp", "--config", "foo.json")))
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--config", "foo.json", "--mcp")))
    }

    @Test
    fun `--help wins over --version when both are present`() {
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--version", "--help")))
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--help", "--version")))
    }

    @Test
    fun `--help wins over unknown args`() {
        // Useful for `mcp-steroid-proxy --foo --help` — the user is asking for
        // help and the unknown token shouldn't override that into Unknown.
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--foo", "--help")))
        assertEquals(CliMode.Help, parseCliMode(arrayOf("-h", "garbage")))
    }

    @Test
    fun `--version wins over unknown args`() {
        assertEquals(CliMode.Version, parseCliMode(arrayOf("--what", "--version")))
        assertEquals(CliMode.Version, parseCliMode(arrayOf("-v", "leftover")))
    }

    // -------------------------- exact-match semantics ----------------------

    @Test
    fun `--MCP is NOT matched (case-sensitive)`() {
        assertTrue(parseCliMode(arrayOf("--MCP")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("--Mcp")) is CliMode.Unknown)
    }

    @Test
    fun `--HELP and -H are NOT matched (case-sensitive)`() {
        assertTrue(parseCliMode(arrayOf("--HELP")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("-H")) is CliMode.Unknown)
    }

    @Test
    fun `--mcp=true is NOT matched (exact-match only)`() {
        // We don't accept `--flag=value` syntax — that's a deliberate constraint
        // so future args can be parsed positionally without a surprising
        // first-class handling of `=`.
        assertTrue(parseCliMode(arrayOf("--mcp=true")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("--help=")) is CliMode.Unknown)
    }

    @Test
    fun `partial-match flags are NOT accepted`() {
        // `--mc` could be a typo for `--mcp`, but accepting prefixes silently is
        // the kind of thing that hides bugs. Be strict.
        assertTrue(parseCliMode(arrayOf("--mc")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("--mcps")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("-help")) is CliMode.Unknown)
    }

    @Test
    fun `positional arg without any recognised flag routes to Unknown`() {
        val mode = parseCliMode(arrayOf("foo"))
        assertTrue(mode is CliMode.Unknown)
        assertEquals(listOf("foo"), (mode as CliMode.Unknown).args)
    }

    @Test
    fun `multiple unknown args are preserved in their original order`() {
        val mode = parseCliMode(arrayOf("--what", "--never", "ever"))
        assertTrue(mode is CliMode.Unknown)
        assertEquals(listOf("--what", "--never", "ever"), (mode as CliMode.Unknown).args)
    }

    // ------------------------- duplicates / weird shapes -------------------

    @Test
    fun `repeated --mcp still routes to mcp`() {
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp", "--mcp")))
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp", "--mcp", "--mcp")))
    }

    @Test
    fun `repeated --help still routes to help`() {
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--help", "--help")))
    }

    @Test
    fun `empty-string arg alone is Unknown, not Help`() {
        // An empty positional token is a weird shape but unambiguously not a flag
        // and should NOT silently fall through to the empty-argv → Help branch.
        val mode = parseCliMode(arrayOf(""))
        assertTrue(mode is CliMode.Unknown)
        assertEquals(listOf(""), (mode as CliMode.Unknown).args)
    }

    @Test
    fun `whitespace-only arg is Unknown`() {
        assertTrue(parseCliMode(arrayOf("   ")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("\t")) is CliMode.Unknown)
    }

    @Test
    fun `--mcp surrounded by whitespace tokens is still mcp`() {
        // The parser doesn't trim — but `--mcp` exact-matches independently of
        // adjacent whitespace tokens, which themselves are just unknown.
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("", "--mcp", "  ")))
    }

    // ------------------------------ object identity ------------------------

    @Test
    fun `CliMode objects are singletons`() {
        // `Mcp`, `Help`, `Version` are sealed-interface `object` instances —
        // pinning identity avoids accidental data-class conversions that would
        // break `===` and `when (mode) { CliMode.Mcp -> ... }` exhaustiveness.
        assertSame(CliMode.Mcp, parseCliMode(arrayOf("--mcp")))
        assertSame(CliMode.Help, parseCliMode(emptyArray()))
        assertSame(CliMode.Help, parseCliMode(arrayOf("--help")))
        assertSame(CliMode.Version, parseCliMode(arrayOf("--version")))
    }

    // ----------------------------- runCli contract ------------------------

    @Test
    fun `runCli Help returns exit 0`() {
        assertEquals(0, runCli(CliMode.Help))
    }

    @Test
    fun `runCli Version returns exit 0`() {
        assertEquals(0, runCli(CliMode.Version))
    }

    @Test
    fun `runCli Unknown returns exit 64`() {
        // 64 is sysexits.h's EX_USAGE — the canonical "bad CLI invocation" code.
        assertEquals(64, runCli(CliMode.Unknown(listOf("--no-such"))))
    }

    @Test
    fun `runCli Mcp throws -- caller must branch to mainImpl`() {
        // Sanity: if someone refactors `main()` to send Mcp through runCli we
        // want to surface that as a hard error rather than silently produce
        // help output on stdout (which would corrupt the MCP transport).
        try {
            runCli(CliMode.Mcp)
            error("runCli(CliMode.Mcp) was expected to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("CliMode.Mcp"), "got: ${e.message}")
        }
    }
}
