/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackDriver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NpxBeaconTest {
    @Test
    fun `constructor registers coroutine cleanup with lifetime`(@TempDir tempDir: Path) {
        val lifetime = CountingCloseableStack()

        NpxBeacon(HomePaths(tempDir.resolve("home")), lifetime)

        assertEquals(1, lifetime.cleanupActionCount)
    }

    @Test
    fun `captureStarted ignores informational and invalid cli modes`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val beacon = NpxBeacon(homePaths, CloseableStackHost())

        beacon.captureStarted(CliMode.Help)
        beacon.captureStarted(CliMode.Version)
        beacon.captureStarted(CliMode.Unknown(listOf("--bad")))

        assertFalse(Files.exists(homePaths.home.resolve(".devrig-user-id")))
    }

    @Test
    fun `distinct id is stored under home paths and reused`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val beacon = NpxBeacon(homePaths, CloseableStackHost())

        val first = beacon.distinctIdForTest()
        val second = beacon.distinctIdForTest()

        assertEquals(first, second)
        assertEquals(first, Files.readString(homePaths.home.resolve(".devrig-user-id")).trim())
        assertTrue(first.isNotBlank())
    }

    @Test
    fun `distinct ids are scoped by home paths`(@TempDir tempDir: Path) {
        val firstBeacon = NpxBeacon(HomePaths(tempDir.resolve("first")), CloseableStackHost())
        val secondBeacon = NpxBeacon(HomePaths(tempDir.resolve("second")), CloseableStackHost())

        assertNotEquals(firstBeacon.distinctIdForTest(), secondBeacon.distinctIdForTest())
    }

    private fun NpxBeacon.distinctIdForTest(): String {
        val method = NpxBeacon::class.java.getDeclaredMethod("distinctId")
        method.isAccessible = true
        return method.invoke(this) as String
    }

    private class CountingCloseableStack : CloseableStack {
        var cleanupActionCount = 0
            private set

        override fun registerCleanupAction(cleanupAction: () -> Unit) {
            cleanupActionCount++
        }

        override fun nestedStack(name: String): CloseableStackDriver {
            error("nestedStack is not expected in NpxBeacon tests")
        }
    }
}
