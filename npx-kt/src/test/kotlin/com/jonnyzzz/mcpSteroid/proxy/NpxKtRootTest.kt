/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

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
        NpxKtRoot.codeSourcePathOverride = null
        NpxKtRoot.resetForTests()
    }

    @Test
    fun `resolves installDist root from jar under lib`(
        @TempDir tempDir: Path,
    ) {
        val root = tempDir.resolve("mcp-steroid-proxy")
        val jar = root.resolve("lib/npx-kt-1.2.3.jar")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "fake jar")
        Files.createDirectories(root.resolve("ij-plugin"))
        Files.createDirectories(root.resolve("7z/mac"))
        Files.writeString(root.resolve("7z/mac/7zz"), "fake binary")

        NpxKtRoot.codeSourcePathOverride = jar
        NpxKtRoot.resetForTests()

        assertEquals(root, NpxKtRoot.path)
        assertEquals(root.resolve("ij-plugin"), NpxKtRoot.ijPluginDir())
        assertEquals(root.resolve("7z"), NpxKtRoot.sevenZipDir())
    }

    @Test
    fun `throws when no package subtree exists next to lib`(
        @TempDir tempDir: Path,
    ) {
        val root = tempDir.resolve("mcp-steroid-proxy")
        val jar = root.resolve("lib/npx-kt-1.2.3.jar")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "fake jar")

        NpxKtRoot.codeSourcePathOverride = jar
        NpxKtRoot.resetForTests()

        val ex = assertFailsWith<IllegalStateException> {
            NpxKtRoot.path
        }
        val message = ex.message.orEmpty()
        assertTrue(message.contains("npx-kt root"), "Expected root diagnostic, got: $message")
        assertTrue(message.contains(jar.toString()), "Expected inspected path in message, got: $message")
    }
}
