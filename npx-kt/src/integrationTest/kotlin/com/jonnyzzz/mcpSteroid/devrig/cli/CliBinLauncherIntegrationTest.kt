/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * End-to-end Docker coverage for the devrig binary OWNING `~/.mcp-steroid/bin/devrig` — the launcher is
 * (re)written on EVERY start, symlinked onto PATH, and the whole chain (wrapper → DEVRIG_JAVA_HOME →
 * install-tree launcher) actually runs. Also pins the undocumented `DEVRIG_BIN_NO_AUTO_REGISTER` gate:
 * OFF by default on this SNAPSHOT dist, ON when the env opt-in is given.
 *
 * Runs the REAL devrig dist inside a throwaway Linux container (see [startDevrigCliContainer]); never on
 * the host, which would create the developer's real `~/.mcp-steroid`.
 */
@Suppress("FunctionName")
class CliBinLauncherIntegrationTest {
    private val lifetime = CloseableStackHost()

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    fun `with the opt-in, every start writes bin devrig, symlinks it onto PATH, and the wrapper runs`() {
        val devrig = lifetime.startDevrigCliContainer()
        // One shell: make a PATH dir under HOME, opt in, start devrig once (self-heal fires), then prove
        // the launcher was written, symlinked, and the symlinked wrapper actually launches devrig.
        val script = """
            set -eu
            mkdir -p "${'$'}HOME/.local/bin"
            export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
            export DEVRIG_BIN_NO_AUTO_REGISTER=false
            "${devrig.launcher}" version >/dev/null 2>&1 || true
            echo "WRAPPER_EXISTS=${'$'}([ -x "${'$'}HOME/.mcp-steroid/bin/devrig" ] && echo yes || echo no)"
            echo "SYMLINK_TARGET=${'$'}(readlink "${'$'}HOME/.local/bin/devrig" 2>/dev/null || echo none)"
            echo "===WRAPPER==="
            cat "${'$'}HOME/.mcp-steroid/bin/devrig"
            echo "===RUN_VIA_SYMLINK==="
            devrig version 2>/dev/null | head -1
        """.trimIndent()

        val out = devrig.runShell(script).assertExitCode(0, "bin-launcher self-heal").stdout

        assertTrue(out.contains("WRAPPER_EXISTS=yes"), out)
        assertTrue(Regex("SYMLINK_TARGET=.*/\\.mcp-steroid/bin/devrig").containsMatchIn(out), out)
        // The wrapper pins the bundled JDK itself and execs the install-tree launcher (absolute path).
        assertTrue(out.contains("DEVRIG_JAVA_HOME="), out)
        assertTrue(out.contains("exec \"${devrig.launcher}\""), out)
        // The whole chain works: invoking the PATH symlink (→ wrapper → DEVRIG_JAVA_HOME → devrig) runs
        // `devrig version`, which prints a real version string (here a SNAPSHOT dist).
        val ranVersion = out.substringAfter("===RUN_VIA_SYMLINK===")
        assertTrue(Regex("\\d+\\.\\d+").containsMatchIn(ranVersion), "the symlinked wrapper should launch devrig:\n$out")
    }

    @Test
    fun `without the opt-in, a SNAPSHOT build does NOT touch bin devrig (disabled by default)`() {
        val devrig = lifetime.startDevrigCliContainer()
        val script = """
            "${devrig.launcher}" version >/dev/null 2>&1 || true
            echo "WRAPPER_EXISTS=${'$'}([ -e "${'$'}HOME/.mcp-steroid/bin/devrig" ] && echo yes || echo no)"
        """.trimIndent()

        val out = devrig.runShell(script).assertExitCode(0, "default-off gate").stdout
        assertTrue(out.contains("WRAPPER_EXISTS=no"), "SNAPSHOT default must not write the launcher:\n$out")
    }

    /** Run a `/bin/sh -c <script>` in the devrig container, returning the finished process result. */
    private fun DevrigCliContainer.runShell(script: String, timeoutSeconds: Long = 120): ProcessResult =
        container.startProcessInContainer {
            args("sh", "-c", script)
                .timeoutSeconds(timeoutSeconds)
                .description("bin-launcher shell")
                .quietly()
        }.awaitForProcessFinish()
}
