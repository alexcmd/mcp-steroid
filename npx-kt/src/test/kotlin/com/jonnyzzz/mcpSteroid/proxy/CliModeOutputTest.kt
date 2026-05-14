/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Pins how [runCli] routes its output. CLI convention:
 *  - Help / version go to **stdout** (so `--help | less`, `--version | awk` work).
 *  - Error variants go to **stderr** (so machine-readable stdout never sees usage spam).
 *
 * Mixing these up has bitten plenty of CLIs in the past — we lock the routing in.
 *
 * The test temporarily replaces `System.out` / `System.err` with byte buffers,
 * runs the unit, then restores the originals. JUnit's `@AfterEach` guarantees
 * restoration even on assertion failure so a single failing case doesn't poison
 * unrelated tests in the suite.
 */
class CliModeOutputTest {

    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream
    private lateinit var outBuf: ByteArrayOutputStream
    private lateinit var errBuf: ByteArrayOutputStream

    @BeforeEach
    fun captureStreams() {
        originalOut = System.out
        originalErr = System.err
        outBuf = ByteArrayOutputStream()
        errBuf = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuf, true, Charsets.UTF_8))
        System.setErr(PrintStream(errBuf, true, Charsets.UTF_8))
    }

    @AfterEach
    fun restoreStreams() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    private fun stdout(): String = outBuf.toString(Charsets.UTF_8)
    private fun stderr(): String = errBuf.toString(Charsets.UTF_8)

    // ------------------------------- Help ----------------------------------

    @Test
    fun `Help writes the usage banner to stdout, nothing to stderr`() {
        val exit = runCli(CliMode.Help)
        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for --help; got: ${stderr()}")
        val out = stdout()
        assertTrue(out.contains("Usage:"), "help output should mention 'Usage:'; got:\n$out")
        assertTrue(out.contains("--mcp"), "help should advertise --mcp; got:\n$out")
        assertTrue(out.contains("--version"), "help should advertise --version; got:\n$out")
        assertTrue(out.contains("--help"), "help should advertise --help itself; got:\n$out")
    }

    @Test
    fun `Help output is line-terminated`() {
        // `command --help | tail -n1` should not see a partial line; the launcher
        // must finish its banner with a newline so shells / piped consumers
        // behave predictably.
        runCli(CliMode.Help)
        assertTrue(stdout().endsWith("\n"), "help output must end with a newline; got: '${stdout().takeLast(20)}'")
    }

    // ------------------------------ Version --------------------------------

    @Test
    fun `Version writes loadProxyVersion()'s value to stdout`() {
        val exit = runCli(CliMode.Version)
        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for --version; got: ${stderr()}")
        val expectedVersion = loadProxyVersion()
        assertEquals("$expectedVersion\n", stdout(),
            "stdout must be exactly the version + newline for `--version`")
    }

    @Test
    fun `Version output is a single line`() {
        // Some monitoring scripts grep `--version | head -1`. Pinning single-line
        // output prevents an accidental multi-line banner sneaking in.
        runCli(CliMode.Version)
        val lines = stdout().trimEnd().lines()
        assertEquals(1, lines.size, "version must be a single line; got: ${stdout()}")
    }

    // ------------------------------ Unknown --------------------------------

    @Test
    fun `Unknown writes an error and the usage banner, both to stderr`() {
        val exit = runCli(CliMode.Unknown(listOf("--no-such", "thing")))
        assertEquals(64, exit)
        assertEquals("", stdout(), "stdout must stay clean for unknown-arg errors; got: ${stdout()}")
        val err = stderr()
        assertTrue(err.contains("Unknown argument(s)"), "stderr should announce the bad input; got:\n$err")
        assertTrue(err.contains("--no-such"), "stderr should echo the offending token; got:\n$err")
        assertTrue(err.contains("thing"), "stderr should echo every offending token; got:\n$err")
        assertTrue(err.contains("Usage:"), "stderr should include the usage banner for orientation; got:\n$err")
    }

    @Test
    fun `Unknown with multiple tokens joins them with a single space`() {
        runCli(CliMode.Unknown(listOf("a", "b", "c")))
        val err = stderr()
        assertTrue(err.contains("Unknown argument(s): a b c"),
            "stderr should join multiple unknown tokens with single spaces; got:\n$err")
    }

    @Test
    fun `Unknown with a single token still produces a coherent error`() {
        runCli(CliMode.Unknown(listOf("--what")))
        val err = stderr()
        assertTrue(err.contains("Unknown argument(s): --what"), "got: $err")
    }
}
