/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NpxUpdateCheckerTest {
    @Test
    fun `checkForUpdates prints stderr notice when remote base differs`() = runTest {
        val err = ByteArrayOutputStream()
        val checker = NpxUpdateChecker(
            currentVersion = "0.95.0-SNAPSHOT-local",
            versionSource = StaticVersionSource("0.96.0"),
            err = PrintStream(err, true, Charsets.UTF_8),
        )

        checker.checkForUpdates()

        val text = err.toString(Charsets.UTF_8)
        assertTrue(text.contains("A new version of $BRAND_NAME is available: 0.96.0"), text)
        assertTrue(text.contains("current: 0.95.0"), text)
        assertTrue(text.contains("https://mcp-steroid.jonnyzzz.com/releases/"), text)
    }

    @Test
    fun `checkForUpdates is quiet when current version starts with remote base`() = runTest {
        val err = ByteArrayOutputStream()
        val checker = NpxUpdateChecker(
            currentVersion = "0.95.0.19999-SNAPSHOT-abcdef",
            versionSource = StaticVersionSource("0.95.0"),
            err = PrintStream(err, true, Charsets.UTF_8),
        )

        checker.checkForUpdates()

        assertEquals("", err.toString(Charsets.UTF_8))
    }

    @Test
    fun `checkForUpdates prints update notice only once per process`() = runTest {
        val err = ByteArrayOutputStream()
        val checker = NpxUpdateChecker(
            currentVersion = "0.95.0",
            versionSource = StaticVersionSource("0.96.0"),
            err = PrintStream(err, true, Charsets.UTF_8),
        )

        checker.checkForUpdates()
        checker.checkForUpdates()

        assertEquals(1, err.toString(Charsets.UTF_8).lineSequence().count { it.isNotBlank() })
    }

    private class StaticVersionSource(private val version: String?) : NpxVersionSource {
        override suspend fun fetchVersionBase(currentVersion: String): String? = version
    }
}
