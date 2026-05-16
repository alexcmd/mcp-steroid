/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.SevenZipLocator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NpxKtRootTest {
    @AfterEach
    fun resetRootOverride() {
        NpxKtRootTestSupport.reset()
    }

    @Test
    fun `resolves installDist root from jar under lib`(
        @TempDir tempDir: Path,
    ) {
        val root = tempDir.resolve("mcp-steroid-proxy")
        val jar = root.resolve("lib/npx-kt-1.2.3.jar")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "fake jar")
        Files.writeString(root.resolve("ij-plugin.zip"), "fake plugin zip")
        Files.createDirectories(root.resolve("7z/mac"))
        Files.writeString(root.resolve("7z/mac/7zz"), "fake binary")

        NpxKtRootTestSupport.overrideCodeSource(jar)

        assertEquals(root, NpxKtRoot.path)
        assertEquals(root.resolve("ij-plugin.zip"), NpxKtRoot.ijPluginZip())
        assertEquals(root.resolve("7z"), NpxKtRoot.sevenZipDir())
    }

    @Test
    fun `throws when no package payload exists next to lib`(
        @TempDir tempDir: Path,
    ) {
        val root = tempDir.resolve("mcp-steroid-proxy")
        val jar = root.resolve("lib/npx-kt-1.2.3.jar")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "fake jar")

        NpxKtRootTestSupport.overrideCodeSource(jar)

        val ex = assertFailsWith<IllegalStateException> {
            NpxKtRoot.path
        }
        val message = ex.message.orEmpty()
        assertTrue(message.contains("npx-kt root"), "Expected root diagnostic, got: $message")
        assertTrue(message.contains(jar.toString()), "Expected inspected path in message, got: $message")
    }

    @Test
    fun `SevenZipLocator prefers installDist bundled file`(
        @TempDir tempDir: Path,
    ) {
        val root = tempDir.resolve("mcp-steroid-proxy")
        val jar = root.resolve("lib/npx-kt-1.2.3.jar")
        val sevenZip = root.resolve("7z/mac/7zz")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "fake jar")
        Files.writeString(root.resolve("ij-plugin.zip"), "fake plugin zip")
        Files.createDirectories(sevenZip.parent)
        Files.writeString(sevenZip, "fake bundled seven zip")
        Files.writeString(sevenZip.parent.resolve("License.txt"), "license")

        NpxKtRootTestSupport.overrideCodeSource(jar)

        val located = SevenZipLocator.locate(os = HostOs.MAC, architecture = HostArchitecture.X86_64)
        requireNotNull(located) { "Expected bundled 7z to resolve from $sevenZip" }
        val locatedPath = Path.of(located)
        assertEquals("fake bundled seven zip", Files.readString(locatedPath))
        assertTrue(Files.isExecutable(locatedPath), "Expected cached 7z copy to be executable: $locatedPath")
    }
}
