/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinLauncherTest {

    // ── env / version gate ──────────────────────────────────────────────────────────────────────

    @Test
    fun `env opt-out disables auto-registration regardless of build`() {
        for (v in listOf("yes", "true", "1", "on", "YES", "True", " on ")) {
            assertFalse(binAutoRegisterEnabled(v), "value '$v' should disable")
        }
    }

    @Test
    fun `env opt-in enables auto-registration even on a SNAPSHOT build`() {
        // This test JVM runs a SNAPSHOT devrig, so the default (unset) is OFF; the explicit opt-in flips it ON.
        for (v in listOf("no", "false", "0", "off", "NO", "False")) {
            assertTrue(binAutoRegisterEnabled(v), "value '$v' should enable")
        }
    }

    @Test
    fun `unset defaults to OFF on this SNAPSHOT test build`() {
        // The generated DevrigVersionMetadata in any non-release build carries "SNAPSHOT", so the default
        // (no env override) must be disabled — which is exactly "disabled by default for tests".
        assertTrue(DevrigVersionMetadata.getDevrigVersion().contains("SNAPSHOT", ignoreCase = true),
            "test build version should be a SNAPSHOT: ${DevrigVersionMetadata.getDevrigVersion()}")
        assertFalse(binAutoRegisterEnabled(null))
        assertFalse(binAutoRegisterEnabled("garbage-unrecognized"))
    }

    // ── launcher rendering ──────────────────────────────────────────────────────────────────────

    @Test
    fun `posix launcher pins DEVRIG_JAVA_HOME (absolute) and execs the install-tree launcher`() {
        val text = renderPosixLauncher(Path.of("/tmp/devrig/bin/devrig"), Path.of("/tmp/jdk-25"))
        assertTrue(text.startsWith("#!/bin/sh\n"), text)
        assertTrue(text.contains("DEVRIG_JAVA_HOME=\"/tmp/jdk-25\"; export DEVRIG_JAVA_HOME"), text)
        assertTrue(text.contains("exec \"/tmp/devrig/bin/devrig\" \"\$@\""), text)
        assertFalse(text.contains("\r\n"), "POSIX launcher must be LF-only")
        assertFalse(text.contains("\$HOME"), "wrapper records absolute paths, not \$HOME-relative")
    }

    @Test
    fun `windows launcher is pure CRLF batch that pins DEVRIG_JAVA_HOME (absolute)`() {
        val text = renderWindowsCmd(Path.of("C:\\devrig\\bin\\devrig.bat"), Path.of("C:\\devrig\\jdk-25"))
        assertTrue(text.startsWith("@echo off\r\n"), text)
        assertTrue(text.contains("set \"DEVRIG_JAVA_HOME=C:\\devrig\\jdk-25\"\r\n"), text)
        assertTrue(text.contains("call \"C:\\devrig\\bin\\devrig.bat\" %*\r\n"), text)
        assertFalse(text.contains("powershell", ignoreCase = true), "windows launcher must not invoke PowerShell")
    }

    @Test
    fun `normalizeLauncher is tolerant of CRLF and trailing newlines`() {
        assertEquals(normalizeLauncher("a\nb\n"), normalizeLauncher("a\r\nb\r\n\n"))
    }

    // ── core self-heal (POSIX) ──────────────────────────────────────────────────────────────────

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `writes an executable bin devrig pointing at the running install and JDK`(@TempDir tmp: Path) {
        val userHome = tmp.resolve("home")
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        val ownRoot = tmp.resolve("opt/devrig") // deliberately OUTSIDE the home — the launcher must still be written
        val ownJava = tmp.resolve("opt/jdk-25")

        ensureBinLauncher(home, isWin = false, ownRoot = ownRoot, ownJava = ownJava, userHome = userHome, pathDirs = emptyList())

        val launcher = home.binDir.resolve("devrig")
        assertTrue(Files.isRegularFile(launcher), "launcher should be written")
        assertTrue(Files.isExecutable(launcher), "launcher should be executable")
        assertEquals(
            renderPosixLauncher(ownRoot.resolve("bin/devrig"), ownJava),
            launcher.readText(),
        )
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `rewriting is idempotent - second start leaves identical bytes`(@TempDir tmp: Path) {
        val userHome = tmp.resolve("home")
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        val ownRoot = tmp.resolve("opt/devrig")
        val ownJava = tmp.resolve("opt/jdk-25")
        fun run() = ensureBinLauncher(home, isWin = false, ownRoot = ownRoot, ownJava = ownJava, userHome = userHome, pathDirs = emptyList())

        run()
        val first = home.binDir.resolve("devrig").readText()
        run()
        assertEquals(first, home.binDir.resolve("devrig").readText())
    }

    // ── PATH symlink (POSIX) ────────────────────────────────────────────────────────────────────

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `symlinks bin devrig into a writable PATH dir under the user home`(@TempDir userHome: Path) {
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        val localBin = Files.createDirectories(userHome.resolve(".local/bin"))

        ensureBinLauncher(
            home, isWin = false,
            ownRoot = home.home.resolve("binaries/devrig-abc"),
            ownJava = home.home.resolve("binaries/jdk-abc"),
            userHome = userHome,
            pathDirs = listOf("/usr/bin", localBin.toString()), // /usr/bin is outside home → skipped
        )

        val link = localBin.resolve("devrig")
        assertTrue(Files.isSymbolicLink(link), "should symlink into the writable PATH dir under home")
        assertEquals(home.binDir.resolve("devrig").toAbsolutePath().normalize(), Files.readSymbolicLink(link))
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `never clobbers a foreign devrig already on PATH`(@TempDir userHome: Path) {
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        val localBin = Files.createDirectories(userHome.resolve(".local/bin"))
        val foreign = localBin.resolve("devrig")
        Files.writeString(foreign, "#!/bin/sh\necho a different devrig\n") // a real file, not our symlink

        ensureBinLauncher(
            home, isWin = false,
            ownRoot = home.home.resolve("binaries/devrig-abc"),
            ownJava = home.home.resolve("binaries/jdk-abc"),
            userHome = userHome,
            pathDirs = listOf(localBin.toString()),
        )

        assertFalse(Files.isSymbolicLink(foreign), "a foreign devrig must be left untouched")
        assertTrue(foreign.readText().contains("a different devrig"))
    }
}
