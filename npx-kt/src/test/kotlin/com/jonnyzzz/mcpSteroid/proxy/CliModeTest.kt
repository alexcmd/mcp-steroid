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

    @Test
    fun `bare backend subcommand routes to Backend Text`() {
        assertEquals(CliMode.Backend.Text, parseCliMode(arrayOf("backend")))
    }

    @Test
    fun `backend --json routes to Backend Json`() {
        assertEquals(CliMode.Backend.Json, parseCliMode(arrayOf("backend", "--json")))
        // Order doesn't matter — `--json backend` is the same thing.
        assertEquals(CliMode.Backend.Json, parseCliMode(arrayOf("--json", "backend")))
    }

    @Test
    fun `backend lifecycle subcommands route to concrete backend modes`() {
        assertEquals(
            CliMode.Backend.Download("idea-community", versionOverride = null, acceptPaid = false),
            parseCliMode(arrayOf("backend", "download", "idea-community")),
        )
        assertEquals(
            CliMode.Backend.Download("idea", versionOverride = "2025.3.3", acceptPaid = true),
            parseCliMode(arrayOf("backend", "download", "idea", "--version", "2025.3.3", "--allow-paid")),
        )
        assertEquals(
            CliMode.Backend.Start("idea-community", versionOverride = "2025.3.3"),
            parseCliMode(arrayOf("backend", "start", "idea-community", "--version", "2025.3.3")),
        )
        assertEquals(
            CliMode.Backend.Stop("idea-community-2025.3.3", versionOverride = null),
            parseCliMode(arrayOf("backend", "stop", "idea-community-2025.3.3")),
        )
    }

    @Test
    fun `backend lifecycle subcommands without id are Unknown`() {
        assertTrue(parseCliMode(arrayOf("backend", "download")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("backend", "start", "--version", "2025.3.3")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("backend", "stop")) is CliMode.Unknown)
    }

    @Test
    fun `--json without backend is ignored at the mode level`() {
        // `--json` only modifies the backend subcommand; on any other mode it's
        // filtered out by the parser, just like `--debug`. So `--json` alone
        // routes to Help (same as no args).
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--json")))
        // And combined with another mode it doesn't override it.
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp", "--json")))
        assertEquals(CliMode.Version, parseCliMode(arrayOf("--version", "--json")))
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--help", "--json")))
    }

    @Test
    fun `--home value is ignored at the mode level`() {
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--home", "/tmp/devrig-home")))
        assertEquals(CliMode.Backend.Text, parseCliMode(arrayOf("--home", "/tmp/devrig-home", "backend")))
        assertEquals(CliMode.Backend.Json, parseCliMode(arrayOf("backend", "--home", "/tmp/devrig-home", "--json")))
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--home", "/tmp/devrig-home", "--mcp")))
    }

    @Test
    fun `parseHomeOverride returns the value immediately after --home`() {
        assertEquals("/tmp/devrig-home", parseHomeOverride(arrayOf("--home", "/tmp/devrig-home")))
        assertEquals("relative", parseHomeOverride(arrayOf("backend", "--home", "relative", "--json")))
        assertEquals(null, parseHomeOverride(emptyArray()))
        assertEquals(null, parseHomeOverride(arrayOf("--home")))
    }

    @Test
    fun `--json with --help still routes to Help (help wins over backend)`() {
        // Subtle: `backend --json --help` could be interpreted as "JSON help".
        // We deliberately keep Help text-only for now — pin that decision so
        // a future JSON-help feature doesn't sneak in by accident.
        assertEquals(CliMode.Help, parseCliMode(arrayOf("backend", "--json", "--help")))
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
    fun `--mcp wins over backend subcommand`() {
        // Defensive: a misconfigured wrapper that combines them should not start
        // the backend listing inside an MCP-framed transport — MCP mode comes first.
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("backend", "--mcp")))
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp", "backend")))
    }

    @Test
    fun `--help wins over backend subcommand`() {
        // `mcp-steroid-proxy backend --help` should print help, NOT open connections
        // to discovered IDEs. Help asks "what does this do?" and the answer is text.
        assertEquals(CliMode.Help, parseCliMode(arrayOf("backend", "--help")))
        assertEquals(CliMode.Help, parseCliMode(arrayOf("-h", "backend")))
    }

    @Test
    fun `--version wins over backend subcommand`() {
        assertEquals(CliMode.Version, parseCliMode(arrayOf("backend", "--version")))
    }

    @Test
    fun `backend with extra unknown args still routes to Backend`() {
        // We don't yet validate subcommand args strictly — extra tokens are accepted
        // so the door stays open for future `backend --json` style options without
        // breaking compatibility.
        assertEquals(CliMode.Backend.Text, parseCliMode(arrayOf("backend", "extra")))
        assertEquals(CliMode.Backend.Text, parseCliMode(arrayOf("extra", "backend")))
    }

    @Test
    fun `backend wins over project when both subcommands are present`() {
        assertEquals(CliMode.Backend.Text, parseCliMode(arrayOf("backend", "project")))
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
    fun `backend subcommand is exact-match - 'BACKEND' or 'Backend' are not accepted`() {
        // Same strictness as the flags: case-mismatch lands in Unknown so the
        // user sees an actionable error instead of a silent no-op.
        assertTrue(parseCliMode(arrayOf("BACKEND")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("Backend")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("back")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("backends")) is CliMode.Unknown)
        assertTrue(parseCliMode(arrayOf("--backend")) is CliMode.Unknown)
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
        // `Mcp`, `Help`, `Version`, `Backend` are sealed-interface `object`
        // instances — pinning identity avoids accidental data-class conversions
        // that would break `===` and `when` exhaustiveness.
        assertSame(CliMode.Mcp, parseCliMode(arrayOf("--mcp")))
        assertSame(CliMode.Help, parseCliMode(emptyArray()))
        assertSame(CliMode.Help, parseCliMode(arrayOf("--help")))
        assertSame(CliMode.Version, parseCliMode(arrayOf("--version")))
        assertSame(CliMode.Backend.Text, parseCliMode(arrayOf("backend")))
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
    fun `parseDebugFlag detects --debug and ignores everything else`() {
        assertTrue(parseDebugFlag(arrayOf("--debug")))
        assertTrue(parseDebugFlag(arrayOf("--mcp", "--debug")))
        assertTrue(parseDebugFlag(arrayOf("--debug", "backend")))
        assertTrue(parseDebugFlag(arrayOf("backend", "--debug", "--json")))
        // Off cases.
        assertTrue(!parseDebugFlag(emptyArray()))
        assertTrue(!parseDebugFlag(arrayOf("--mcp")))
        assertTrue(!parseDebugFlag(arrayOf("--Debug")), "case-sensitive like the rest of the parser")
        assertTrue(!parseDebugFlag(arrayOf("--debug-foo")), "exact-match only, no prefix")
    }

    @Test
    fun `applyDebugLogging sets proxy_log_level system property only when --debug is true`() {
        val key = "proxy.log.level"
        val original = System.getProperty(key)
        try {
            // --debug off ⇒ property is left as-is. We pre-set a sentinel to
            // confirm the function doesn't overwrite externally-supplied levels.
            System.setProperty(key, "INFO")
            applyDebugLogging(false)
            assertEquals("INFO", System.getProperty(key),
                "applyDebugLogging(false) must NOT overwrite an externally-set value")

            // --debug on ⇒ property is forced to DEBUG.
            applyDebugLogging(true)
            assertEquals("DEBUG", System.getProperty(key))
        } finally {
            if (original == null) System.clearProperty(key) else System.setProperty(key, original)
        }
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
