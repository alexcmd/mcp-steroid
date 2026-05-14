/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.cli

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.NpxKtMcpInstaller
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * End-to-end coverage of the **non-MCP** CLI surface — `--help`, `--version`,
 * no-args, and unknown-arg paths — driven through the real `installDist`
 * launcher (`bin/mcp-steroid-proxy` / `.bat`).
 *
 * The unit tests in `CliModeTest` / `CliModeOutputTest` already pin the parser
 * and routing behaviour inside the JVM; this class extends that to the shell
 * launcher script and JNI boundaries. The same launcher is exercised in MCP
 * mode by `CliMcpStdioIntegrationTest` and for stdout-cleanliness by
 * `CliMcpStdioStdoutCleanlinessTest` — together those three classes form the
 * full launcher contract.
 *
 * Why this isn't covered by the unit tests alone: a `System.setOut` shim,
 * a logback config that fires before `main()`, or a startup banner in the
 * application-plugin-generated start script could each violate the routing
 * contract without any in-JVM test ever seeing it. Spawning the actual binary
 * is the only way to verify the user-visible behaviour.
 */
class CliOptionsIntegrationTest {

    private val installDir: File by lazy { NpxKtMcpInstaller.resolveInstallDir() }
    private val lifetime = CloseableStackHost()

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    private fun launcher(): File {
        val isWindows = "windows" in System.getProperty("os.name").lowercase(Locale.ROOT)
        val name = if (isWindows) "mcp-steroid-proxy.bat" else "mcp-steroid-proxy"
        val file = File(installDir, "bin/$name")
        check(file.isFile) { "launcher missing at ${file.absolutePath}" }
        if (!isWindows) {
            check(file.canExecute()) { "launcher is not executable: ${file.absolutePath}" }
        }
        return file
    }

    private fun runLauncher(vararg args: String): ProcessResult {
        val isWindows = "windows" in System.getProperty("os.name").lowercase(Locale.ROOT)
        val command = if (isWindows) {
            listOf("cmd.exe", "/c", launcher().absolutePath) + args
        } else {
            listOf(launcher().absolutePath) + args
        }
        return RunProcessRequest()
            .command(command)
            .logPrefix("npx-kt-cli")
            .description("npx-kt CLI surface: ${args.joinToString(" ")}")
            .timeoutSeconds(30)
            .quietly()
            .startProcess()
            .awaitForProcessFinish()
    }

    // -------------------------------- --help --------------------------------

    @Test
    fun `--help exits 0 with usage on stdout and clean stderr`() {
        val r = runLauncher("--help")
        assertEquals(0, r.exitCode, "--help must exit 0; stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.contains("Usage:"), "expected 'Usage:' in stdout; got:\n${r.stdout}")
        assertTrue(r.stdout.contains("--mcp"), "help banner must advertise --mcp; got:\n${r.stdout}")
        assertTrue(r.stdout.contains("--version"), "help banner must advertise --version; got:\n${r.stdout}")
        assertTrue(r.stderr.isBlank(),
            "--help must keep stderr clean; got:\n${r.stderr}")
    }

    @Test
    fun `-h short form exits 0 with the same usage banner on stdout`() {
        val r = runLauncher("-h")
        assertEquals(0, r.exitCode, "-h must exit 0; stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.contains("Usage:"), "got:\n${r.stdout}")
    }

    @Test
    fun `no args at all also prints help and exits 0`() {
        // The launcher should be inspectable with bare `mcp-steroid-proxy` —
        // no stdin consumed, no NDJSON written, no client confusion.
        val r = runLauncher()
        assertEquals(0, r.exitCode, "bare invocation must exit 0; stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.contains("Usage:"), "got:\n${r.stdout}")
        assertTrue(r.stderr.isBlank(), "no-arg invocation must keep stderr clean; got:\n${r.stderr}")
    }

    // ------------------------------ --version -------------------------------

    @Test
    fun `--version prints a single non-empty line on stdout`() {
        val r = runLauncher("--version")
        assertEquals(0, r.exitCode, "--version must exit 0; stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stderr.isBlank(), "--version must keep stderr clean; got:\n${r.stderr}")
        val lines = r.stdout.trimEnd().lines()
        assertEquals(1, lines.size,
            "--version must be a single line; got ${lines.size} lines:\n${r.stdout}")
        assertTrue(lines.single().isNotBlank(), "--version line must be non-empty")
    }

    @Test
    fun `-v short form behaves identically to --version`() {
        val long = runLauncher("--version")
        val short = runLauncher("-v")
        assertEquals(long.exitCode, short.exitCode, "long vs short version exit codes")
        assertEquals(long.stdout.trim(), short.stdout.trim(),
            "long vs short version output must match")
    }

    // --------------------------- unknown / error path -----------------------

    @Test
    fun `unknown arg exits 64 with error on stderr and clean stdout`() {
        val r = runLauncher("--no-such-flag")
        assertEquals(64, r.exitCode,
            "unknown flag must exit 64 (sysexits EX_USAGE); stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.isBlank(),
            "unknown-arg path must keep stdout clean so machine consumers aren't confused; got:\n${r.stdout}")
        assertTrue(r.stderr.contains("Unknown argument(s)"),
            "stderr should announce 'Unknown argument(s)'; got:\n${r.stderr}")
        assertTrue(r.stderr.contains("--no-such-flag"),
            "stderr should echo the offending token; got:\n${r.stderr}")
        assertTrue(r.stderr.contains("Usage:"),
            "stderr should include the usage banner for orientation; got:\n${r.stderr}")
    }

    @Test
    fun `multiple unknown args are all surfaced in the error message`() {
        val r = runLauncher("--alpha", "--beta")
        assertEquals(64, r.exitCode)
        assertTrue(r.stderr.contains("--alpha"), "got:\n${r.stderr}")
        assertTrue(r.stderr.contains("--beta"), "got:\n${r.stderr}")
    }

    // --------------------- mixed-flag precedence (real binary) --------------

    @Test
    fun `--help mixed with an unknown arg still routes to help`() {
        // Parser unit tests pin this; verify the real launcher honours it too
        // so a future shell-launcher tweak doesn't reorder args.
        val r = runLauncher("--bogus", "--help")
        assertEquals(0, r.exitCode, "--help should win over unknown args; stderr=\n${r.stderr}")
        assertTrue(r.stdout.contains("Usage:"), "got:\n${r.stdout}")
    }

    @Test
    fun `case-mismatch on --MCP is treated as Unknown, not as MCP`() {
        // Critical safety net: an accidental upper-case flag MUST NOT silently
        // commit stdout to NDJSON framing. The launcher should error out
        // visibly so the user fixes their wrapper script.
        val r = runLauncher("--MCP")
        assertEquals(64, r.exitCode, "--MCP (wrong case) must NOT trigger MCP mode")
        assertTrue(r.stderr.contains("Unknown argument(s)"), "got:\n${r.stderr}")
    }
}
