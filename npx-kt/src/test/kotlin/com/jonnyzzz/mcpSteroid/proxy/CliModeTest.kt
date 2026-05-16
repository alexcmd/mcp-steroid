/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pins the contract of [parseCliMode]. The launcher's `main()` calls this as its
 * very first statement and redirects `System.out` → stderr the moment it sees
 * `CliMode.Mcp` — so any misclassification here either silently breaks MCP
 * framing (false-positive `Mcp`) or commits stdout to NDJSON when the user just
 * wanted `--help` (false-negative). Cover edges, not just happy paths.
 */
class CliModeTest {
    @TempDir
    lateinit var testHome: Path

    private fun testHomePaths(): HomePaths = HomePaths(testHome).also { it.mkdirsAll() }

    private fun runCliForTest(mode: CliMode): Int = runCli(mode, testHomePaths())

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

    @TestFactory
    fun `help and version selectors override positional data modes`(): List<DynamicTest> {
        val cases = listOf(
            arrayOf("backend", "--help") to CliMode.Help,
            arrayOf("backend", "download", "--help") to CliMode.Help,
            arrayOf("backend", "download", "idea-community", "--help") to CliMode.Help,
            arrayOf("backend", "--version") to CliMode.Version,
            arrayOf("backend", "download", "-v") to CliMode.Version,
            arrayOf("project", "--help") to CliMode.Help,
            arrayOf("project", "--version") to CliMode.Version,
            arrayOf("backend", "provision", "--help") to CliMode.Help,
        )

        return cases.map { (args, expected) ->
            DynamicTest.dynamicTest("${args.joinToString(" ")} -> $expected") {
                assertEquals(expected, parseCliMode(args))
            }
        }
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
    fun `bare project subcommand routes to Project Text`() {
        assertEquals(CliMode.Project.Text, parseCliMode(arrayOf("project")))
    }

    @Test
    fun `project --json routes to Project Json`() {
        assertEquals(CliMode.Project.Json, parseCliMode(arrayOf("project", "--json")))
    }

    @Test
    fun `--json project routes to Project Json`() {
        assertEquals(CliMode.Project.Json, parseCliMode(arrayOf("--json", "project")))
    }

    @Test
    fun `backend lifecycle subcommands route to concrete backend modes`() {
        assertEquals(
            CliMode.Backend.Download("idea-community", versionOverride = null),
            parseCliMode(arrayOf("backend", "download", "idea-community")),
        )
        assertEquals(
            CliMode.Backend.Download("idea-community", versionOverride = null, json = true),
            parseCliMode(arrayOf("backend", "download", "idea-community", "--json")),
        )
        assertEquals(
            CliMode.Backend.Download("idea-ultimate", versionOverride = "2025.3.3"),
            parseCliMode(arrayOf("backend", "download", "idea-ultimate", "--version", "2025.3.3")),
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
    fun `backend download without id routes to default listing`() {
        assertEquals(CliMode.Backend.DownloadList(json = false), parseCliMode(arrayOf("backend", "download")))
        assertEquals(CliMode.Backend.DownloadList(json = true), parseCliMode(arrayOf("backend", "download", "--json")))
    }

    @Test
    fun `backend provision without id routes to port-discovery listing`() {
        assertEquals(CliMode.Backend.ProvisionList(json = false), parseCliMode(arrayOf("backend", "provision")))
        assertEquals(CliMode.Backend.ProvisionList(json = true), parseCliMode(arrayOf("backend", "provision", "--json")))
    }

    @Test
    fun `backend provision with port id routes to concrete provision action`() {
        assertEquals(CliMode.Backend.Provision("port-63342", json = false), parseCliMode(arrayOf("backend", "provision", "port-63342")))
        assertEquals(CliMode.Backend.Provision("port-63342", json = true), parseCliMode(arrayOf("backend", "provision", "port-63342", "--json")))
    }

    @Test
    fun `--allow-paid is rejected because paid downloads no longer require consent flags`() {
        val mode = parseCliMode(arrayOf("backend", "download", "--allow-paid"))

        assertTrue(mode is CliMode.Unknown)
        mode as CliMode.Unknown
        assertEquals(listOf("--allow-paid"), mode.args)
        assertEquals("The --allow-paid flag was removed; requested JetBrains binaries are downloaded without a CLI consent flag.", mode.hint)
    }

    @Test
    fun `backend start and stop without id route to default listings`() {
        assertEquals(CliMode.Backend.StartList(json = false), parseCliMode(arrayOf("backend", "start")))
        assertEquals(CliMode.Backend.StartList(json = true), parseCliMode(arrayOf("backend", "start", "--json")))
        assertEquals(CliMode.Backend.StartList(json = false), parseCliMode(arrayOf("backend", "start", "--version", "2025.3.3")))
        assertEquals(CliMode.Backend.StopList(json = false), parseCliMode(arrayOf("backend", "stop")))
        assertEquals(CliMode.Backend.StopList(json = true), parseCliMode(arrayOf("backend", "stop", "--json")))
    }

    @Test
    fun `backend download rejects unknown product key with list hint`() {
        val mode = parseCliMode(arrayOf("backend", "download", "foo"))
        assertTrue(mode is CliMode.Unknown)
        mode as CliMode.Unknown
        assertEquals(listOf("backend", "download", "foo"), mode.args)
        assertEquals("Run `devrig backend download` with no id to list valid backend ids.", mode.hint)
    }

    @Test
    fun `backend start and stop reject unknown ids with list hints`() {
        val start = parseCliMode(arrayOf("backend", "start", "foo"))
        assertTrue(start is CliMode.Unknown)
        start as CliMode.Unknown
        assertEquals("Run `devrig backend start` with no id to list valid backend ids.", start.hint)

        val stop = parseCliMode(arrayOf("backend", "stop", "foo"))
        assertTrue(stop is CliMode.Unknown)
        stop as CliMode.Unknown
        assertEquals("Run `devrig backend stop` with no id to list valid backend ids.", stop.hint)
    }

    @Test
    fun `backend provision rejects non-port ids with list hint`() {
        val mode = parseCliMode(arrayOf("backend", "provision", "idea-community"))
        assertTrue(mode is CliMode.Unknown)
        mode as CliMode.Unknown
        assertEquals(listOf("backend", "provision", "idea-community"), mode.args)
        assertEquals("Run `devrig backend provision` with no id to list valid backend ids.", mode.hint)
    }

    @Test
    fun `--json without data mode is rejected unless an info selector is present`() {
        assertTrue(parseCliMode(arrayOf("--json")) is CliMode.Unknown)
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp", "--json")))
        assertEquals(CliMode.Version, parseCliMode(arrayOf("--version", "--json")))
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--help", "--json")))
    }

    @Test
    fun `--home is rejected outside MCP mode`() {
        val topLevel = parseCliMode(arrayOf("--home", "/tmp/devrig-home"))
        assertTrue(topLevel is CliMode.Unknown)
        topLevel as CliMode.Unknown
        assertEquals(listOf("--home"), topLevel.args)
        assertEquals("Unknown flag: --home", topLevel.hint)

        val backend = parseCliMode(arrayOf("--home", "/tmp/devrig-home", "backend"))
        assertTrue(backend is CliMode.Unknown)
        backend as CliMode.Unknown
        assertEquals(listOf("--home"), backend.args)
        assertEquals("Unknown flag: --home", backend.hint)

        val backendJson = parseCliMode(arrayOf("backend", "--home", "/tmp/devrig-home", "--json"))
        assertTrue(backendJson is CliMode.Unknown)
        backendJson as CliMode.Unknown
        assertEquals(listOf("--home"), backendJson.args)
        assertEquals("Unknown flag: --home", backendJson.hint)
    }

    @Test
    fun `--mcp still wins over removed home flag`() {
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--home", "/tmp/devrig-home", "--mcp")))
    }

    @Test
    fun `--json with --help after backend routes to Help before backend allow-list`() {
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
    fun `--mcp wins over project subcommand`() {
        assertEquals(CliMode.Mcp, parseCliMode(arrayOf("--mcp", "project", "--json")))
    }

    @Test
    fun `mcp ignored token list excludes global flags that still apply`() {
        assertEquals(listOf("backend"), mcpIgnoredTokens(arrayOf("--mcp", "--debug", "backend")))
        assertEquals(
            listOf("--home", "/tmp/devrig-home", "backend", "--json"),
            mcpIgnoredTokens(arrayOf("--home", "/tmp/devrig-home", "--mcp", "backend", "--json")),
        )
    }

    @Test
    fun `--help combined with backend tokens routes to Help outside MCP mode`() {
        assertEquals(CliMode.Help, parseCliMode(arrayOf("backend", "--help")))
        assertEquals(CliMode.Help, parseCliMode(arrayOf("-h", "backend")))
    }

    @Test
    fun `--help combined with project tokens routes to Help outside MCP mode`() {
        assertEquals(CliMode.Help, parseCliMode(arrayOf("project", "--help")))
    }

    @Test
    fun `--version after backend info mode routes to Version`() {
        assertEquals(CliMode.Version, parseCliMode(arrayOf("backend", "--version")))
    }

    @Test
    fun `backend with extra positional args routes to Unknown`() {
        val trailing = parseCliMode(arrayOf("backend", "extra"))
        assertTrue(trailing is CliMode.Unknown)
        trailing as CliMode.Unknown
        assertEquals("Unexpected extra argument: extra", trailing.hint)

        val leading = parseCliMode(arrayOf("extra", "backend"))
        assertTrue(leading is CliMode.Unknown)
        leading as CliMode.Unknown
        assertEquals(listOf("extra", "backend"), leading.args)
    }

    @Test
    fun `backend with project as an extra positional is rejected`() {
        val mode = parseCliMode(arrayOf("backend", "project"))
        assertTrue(mode is CliMode.Unknown)
        mode as CliMode.Unknown
        assertEquals("Unexpected extra argument: project", mode.hint)
    }

    @Test
    fun `--help wins over --version when both info selectors are present`() {
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--version", "--help")))
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--help", "--version")))
    }

    @Test
    fun `--help overrides unknown flags or extra args`() {
        assertEquals(CliMode.Help, parseCliMode(arrayOf("--foo", "--help")))
        assertEquals(CliMode.Help, parseCliMode(arrayOf("-h", "garbage")))
    }

    @Test
    fun `--version overrides unknown flags or extra args`() {
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
    fun `first unknown flag is reported with a hint`() {
        val mode = parseCliMode(arrayOf("--what", "--never", "ever"))
        assertTrue(mode is CliMode.Unknown)
        mode as CliMode.Unknown
        assertEquals(listOf("--what"), mode.args)
        assertEquals("Unknown flag: --what", mode.hint)
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
        assertSame(CliMode.Project.Text, parseCliMode(arrayOf("project")))
    }

    // ----------------------------- runCli contract ------------------------

    @Test
    fun `runCli Help returns exit 0`() {
        assertEquals(0, runCliForTest(CliMode.Help))
    }

    @Test
    fun `runCli Version returns exit 0`() {
        assertEquals(0, runCliForTest(CliMode.Version))
    }

    @Test
    fun `runCli Unknown returns exit 64`() {
        // 64 is sysexits.h's EX_USAGE — the canonical "bad CLI invocation" code.
        assertEquals(64, runCliForTest(CliMode.Unknown(listOf("--no-such"))))
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
            runCliForTest(CliMode.Mcp)
            error("runCli(CliMode.Mcp) was expected to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("CliMode.Mcp"), "got: ${e.message}")
        }
    }
}
