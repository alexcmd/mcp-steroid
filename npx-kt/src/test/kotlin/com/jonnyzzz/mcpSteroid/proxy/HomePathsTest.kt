/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomePathsTest {

    @Test
    fun `CLI override wins over environment and resolves to an absolute normalized path`(
        @TempDir tempDir: Path,
    ) {
        val override = tempDir.resolve("x").resolve("..").resolve("chosen").toString()
        val envHome = tempDir.resolve("env").toString()

        val paths = resolveHomePaths(override, env = mapOf("MCP_STEROID_HOME" to envHome))

        assertEquals(tempDir.resolve("chosen").toAbsolutePath().normalize(), paths.home)
    }

    @Test
    fun `environment wins over default when CLI override is absent`(
        @TempDir tempDir: Path,
    ) {
        val envHome = tempDir.resolve("env-home").toString()

        val paths = resolveHomePaths(null, env = mapOf("MCP_STEROID_HOME" to envHome))

        assertEquals(tempDir.resolve("env-home").toAbsolutePath().normalize(), paths.home)
    }

    @Test
    fun `blank environment falls back to user home default`() {
        val expected = Path.of(System.getProperty("user.home"), ".mcp-steroid")
            .toAbsolutePath()
            .normalize()

        val paths = resolveHomePaths(null, env = mapOf("MCP_STEROID_HOME" to "   "))

        assertEquals(expected, paths.home)
    }

    @Test
    fun `derived paths use the required managed backend layout`(
        @TempDir tempDir: Path,
    ) {
        val paths = HomePaths(tempDir)

        assertEquals(tempDir.resolve("logs"), paths.logsDir)
        assertEquals(tempDir.resolve("backends"), paths.backendsDir)
        assertEquals(tempDir.resolve("caches"), paths.cachesDir)
        assertEquals(tempDir.resolve("downloads"), paths.downloadsDir)
        assertEquals(tempDir.resolve("state"), paths.stateDir)
        assertEquals(tempDir.resolve("markers"), paths.markersDir)
        assertEquals(tempDir.resolve("execution-storage"), paths.executionStorageDir)
        assertEquals(tempDir.resolve("backends/idea-community-2025.3.3"), paths.backendDir("idea-community-2025.3.3"))
        assertEquals(tempDir.resolve("caches/idea-community-2025.3.3"), paths.cacheDir("idea-community-2025.3.3"))
        assertEquals(tempDir.resolve("state/idea-community-2025.3.3.pid"), paths.pidFile("idea-community-2025.3.3"))
    }

    @Test
    fun `mkdirsAll creates the writable roots and is idempotent`(
        @TempDir tempDir: Path,
    ) {
        val paths = HomePaths(tempDir.resolve("home"))

        paths.mkdirsAll()
        paths.mkdirsAll()

        listOf(paths.logsDir, paths.backendsDir, paths.cachesDir, paths.downloadsDir, paths.stateDir, paths.markersDir).forEach { dir ->
            assertTrue(dir.isDirectory(), "$dir should be a directory")
        }
        assertTrue(!Files.exists(paths.executionStorageDir), "execution-storage is reserved and not created yet")
    }

    @Test
    fun `migrateLegacyArchives moves old archive files into downloads and is idempotent`(
        @TempDir tempDir: Path,
    ) {
        val paths = HomePaths(tempDir.resolve("home"))
        val legacyDir = paths.cachesDir.resolve("_archives")
        val archiveName = "ideaIC-2025.3.3.tar.gz"
        Files.createDirectories(legacyDir)
        Files.writeString(legacyDir.resolve(archiveName), "archive bytes")

        migrateLegacyArchives(paths)
        migrateLegacyArchives(paths)

        val migratedArchive = paths.downloadsDir.resolve(archiveName)
        assertTrue(Files.isRegularFile(migratedArchive), "archive should move into downloads/")
        assertEquals("archive bytes", Files.readString(migratedArchive))
        assertTrue(!Files.exists(legacyDir), "empty legacy archive directory should be deleted")
    }
}
