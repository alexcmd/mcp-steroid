/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VmOptionsWriterTest {

    @Test
    fun `writes exact vmoptions content as sibling of bundle directory`(
        @TempDir tempDir: Path,
    ) {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val id = "idea-community-2025.3.3"

        val written = writeBackendVmOptions(homePaths, id, "IntelliJ IDEA CE.app")

        val expectedPath = homePaths.backendDir(id).resolve("IntelliJ IDEA CE.app.vmoptions")
        assertEquals(expectedPath, written)

        val cacheDir = homePaths.cacheDir(id).toAbsolutePath().normalize()
        val expected = listOf(
            "-Didea.config.path=${cacheDir.resolve("config")}",
            "-Didea.system.path=${cacheDir.resolve("system")}",
            "-Didea.log.path=${cacheDir.resolve("logs")}",
            "-Didea.plugins.path=${cacheDir.resolve("plugins")}",
            "-Didea.vendor.name=devrig (managed)",
            "-Xms256m",
            "-Xmx2048m",
            "",
        ).joinToString("\n")

        val content = Files.readString(written)
        assertEquals(expected, content)
        assertFalse(content.contains("\r"), "vmoptions must use LF line endings only")

        listOf("config", "system", "logs", "plugins").forEach { child ->
            assertTrue(homePaths.cacheDir(id).resolve(child).exists(), "$child cache directory should exist")
        }
        assertFalse(homePaths.backendDir(id).resolve("IntelliJ IDEA CE.app/Contents/bin/idea.vmoptions").exists(),
            "vmoptions must be a sibling to the bundle, not written inside the signed macOS app")
    }
}
