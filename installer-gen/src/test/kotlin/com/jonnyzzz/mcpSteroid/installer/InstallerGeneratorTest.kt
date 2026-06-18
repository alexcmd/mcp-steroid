/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstallerGeneratorTest {
    // ── platform-key adapter ─────────────────────────────────────────────────────────────────────

    @Test
    fun `scriptKey maps enums to the os-cpu strings the scripts use (aarch64 - arm64)`() {
        assertEquals("linux-x64", JdkPlatform(JdkOs.LINUX, JdkArch.X64).scriptKey())
        assertEquals("linux-arm64", JdkPlatform(JdkOs.LINUX, JdkArch.AARCH64).scriptKey())
        assertEquals("macos-arm64", JdkPlatform(JdkOs.MACOS, JdkArch.AARCH64).scriptKey())
        assertEquals("windows-x64", JdkPlatform(JdkOs.WINDOWS, JdkArch.X64).scriptKey())
        assertEquals("windows-arm64", JdkPlatform(JdkOs.WINDOWS, JdkArch.AARCH64).scriptKey())
    }

    // ── jdkScriptTable: covers exactly the 5 installer platforms ─────────────────────────────────

    private fun art(platform: JdkPlatform, archive: ArchiveType, javaHome: String) = JdkArtifact(
        platform = platform, vendor = "v", version = "25.0.3.9.1", featureVersion = 25, archive = archive,
        url = "https://example.com/jdk.${archive.extension}", fileName = "jdk.${archive.extension}",
        size = 1, sha256 = "a".repeat(64), javaHome = javaHome,
    )

    private fun fullModel() = JdkModel(
        listOf(
            art(JdkPlatform(JdkOs.LINUX, JdkArch.X64), ArchiveType.TAR_GZ, "jdk-x64"),
            art(JdkPlatform(JdkOs.LINUX, JdkArch.AARCH64), ArchiveType.TAR_GZ, "jdk-arm64"),
            art(JdkPlatform(JdkOs.MACOS, JdkArch.AARCH64), ArchiveType.TAR_GZ, "jdk.jdk/Contents/Home"),
            art(JdkPlatform(JdkOs.WINDOWS, JdkArch.X64), ArchiveType.ZIP, "jdk-win"),
            art(JdkPlatform(JdkOs.WINDOWS, JdkArch.AARCH64), ArchiveType.ZIP, "zulu-win"),
        )
    )

    @Test
    fun `jdkScriptTable accepts exactly the 5 platforms`() {
        assertEquals(ALL_PLATFORMS.toSet(), jdkScriptTable(fullModel()).keys)
    }

    @Test
    fun `jdkScriptTable fails when a platform is missing`() {
        val partial = JdkModel(fullModel().jdks.dropLast(1)) // drop windows-arm64
        val ex = assertFailsWith<IllegalArgumentException> { jdkScriptTable(partial) }
        assertTrue(ex.message!!.contains("missing installer platforms"), ex.message!!)
    }

    @Test
    fun `validateScriptTable rejects a non-hex sha256 and an absolute javaHome`() {
        val badSha = mapOf(*ALL_PLATFORMS.map { it to JdkScriptEntry("https://x", "ZZZ", "zip", "h") }.toTypedArray())
        assertFailsWith<IllegalArgumentException> { validateScriptTable(badSha) }
        val absHome = mapOf(*ALL_PLATFORMS.map { it to JdkScriptEntry("https://x", "a".repeat(64), "zip", "/abs") }.toTypedArray())
        assertFailsWith<IllegalArgumentException> { validateScriptTable(absHome) }
    }

    // ── render pipeline: scripts bake the table + carry the musl guard, no leftover placeholders ─

    private val devrig = DevrigEntry(url = "https://example.com/devrig-1.0.zip", sha256 = "b".repeat(64))

    @Test
    fun `renderInstallerScripts bakes every platform and leaves no placeholder`() {
        val table = jdkScriptTable(fullModel())
        val scripts = renderInstallerScripts(table, devrig, "1.2.3")

        // No unresolved @@…@@ placeholders survived.
        assertTrue(!scripts.sh.contains("@@") && !scripts.ps.contains("@@"), "placeholders left unresolved")

        // install.sh: the 3 POSIX platform arms + the musl-fail guard + the devrig coordinates.
        listOf("macos-arm64)", "linux-arm64)", "linux-x64)").forEach {
            assertTrue(scripts.sh.contains(it), "install.sh missing arm $it")
        }
        assertTrue(scripts.sh.contains("musl libc (Alpine) is not supported"), "install.sh missing musl guard")
        assertTrue(scripts.sh.contains("devrig-1.2.3/bin/devrig"), "install.sh missing devrig binsub")
        assertTrue(scripts.sh.contains("DEVRIG_URL='https://example.com/devrig-1.0.zip'"), "install.sh missing devrig url")

        // install.ps1: the 2 Windows entries + devrig.bat binsub.
        listOf("'windows-x64' = @{", "'windows-arm64' = @{").forEach {
            assertTrue(scripts.ps.contains(it), "install.ps1 missing entry $it")
        }
        assertTrue(scripts.ps.contains("devrig-1.2.3/bin/devrig.bat"), "install.ps1 missing devrig binsub")
    }

    // ── devrig resolution: local override path (no network) ──────────────────────────────────────

    @Test
    fun `resolveDevrig from a local zip computes its sha256 and records the public url`(@TempDir dir: Path) {
        val zip = dir.resolve("devrig.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("devrig-1.0/bin/devrig")); z.write("#!/bin/sh".encodeToByteArray()); z.closeEntry()
        }
        val flags = mapOf("devrig-zip" to listOf(zip.toString()), "devrig-url" to listOf("https://example.com/devrig-1.0.zip"))

        val noNetwork = object : HttpFetcher {
            override fun head(url: String) = error("no network expected for the local-zip path")
            override fun getBytes(url: String) = error("no network expected for the local-zip path")
        }
        val devrig = resolveDevrig(flags, noNetwork)
        assertEquals("https://example.com/devrig-1.0.zip", devrig.url)
        assertEquals(sha256Hex(Files.readAllBytes(zip)), devrig.sha256)
        validateDevrig(devrig) // must pass
    }

    @Test
    fun `validateDevrig rejects a placeholder url`() {
        assertFailsWith<IllegalArgumentException> {
            validateDevrig(DevrigEntry(url = "https://example.com/PLACEHOLDER.zip", sha256 = "b".repeat(64)))
        }
    }
}
