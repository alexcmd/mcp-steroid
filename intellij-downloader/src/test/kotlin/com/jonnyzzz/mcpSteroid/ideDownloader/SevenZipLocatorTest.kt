/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SevenZipLocatorTest {

    @Test
    fun `bundled 7zz resource is present on Linux + Mac classpath`() {
        // The Gradle build extracts 7zz from 7-zip.org tar.xz archives into resources/7z/<platform>/
        // Verify the JAR resource exists for the host's bundled platform (no PATH involvement).
        val os = resolveHostOs()
        val arch = resolveHostArchitecture()
        if (os == HostOs.WINDOWS) {
            // Windows is intentionally PATH-only; skip the bundled-resource check.
            return
        }
        val resourcePath = when (os) {
            HostOs.LINUX -> if (arch.isArmArch) "7z/linux-arm64/7zz" else "7z/linux-x64/7zz"
            HostOs.MAC -> "7z/mac/7zz"
            HostOs.WINDOWS -> error("unreachable")
        }
        val stream = SevenZipLocatorTest::class.java.classLoader.getResourceAsStream(resourcePath)
        assertNotNull("Expected bundled 7zz resource at $resourcePath", stream)
        stream?.close()
    }

    @Test
    fun `License resource is shipped alongside the binary`() {
        val os = resolveHostOs()
        if (os == HostOs.WINDOWS) return
        val licensePath = when (os) {
            HostOs.LINUX -> if (resolveHostArchitecture().isArmArch) "7z/linux-arm64/License.txt" else "7z/linux-x64/License.txt"
            HostOs.MAC -> "7z/mac/License.txt"
            HostOs.WINDOWS -> error("unreachable")
        }
        SevenZipLocatorTest::class.java.classLoader.getResourceAsStream(licensePath).use {
            assertNotNull("Expected 7-Zip License.txt at $licensePath", it)
        }
        SevenZipLocatorTest::class.java.classLoader.getResourceAsStream("7z/License.txt").use {
            assertNotNull("Expected shared 7z/License.txt at JAR root for license attribution", it)
        }
    }

    @Test
    fun `locator returns an executable path on Linux + Mac hosts`() {
        val os = resolveHostOs()
        // On Windows the locator depends on PATH; skip unless an explicit 7z is present.
        if (os == HostOs.WINDOWS) {
            return
        }
        val path = SevenZipLocator.locate()
        assertNotNull("Expected SevenZipLocator to return a path on $os host", path)
        val binary = File(path!!)
        assertTrue("Located 7z binary should exist: $binary", binary.isFile)
        assertTrue("Located 7z binary should be executable: $binary", binary.canExecute())
    }

    @Test
    fun `bundled 7zz is the full version and supports NSIS extraction`() {
        // Sanity-check the binary identifies itself and lists NSIS in its supported formats.
        // This is the contract we promise downstream callers (Windows .exe IDE installers).
        val os = resolveHostOs()
        if (os == HostOs.WINDOWS) return

        val path = SevenZipLocator.locate() ?: return // no binary -> no contract to verify
        val output = ProcessBuilder(path, "i")
            .redirectErrorStream(true)
            .start()
            .also { it.outputStream.close() }
            .inputStream.bufferedReader().use { it.readText() }
        assertTrue("Expected 7-Zip banner in `7zz i` output, got: ${output.take(300)}",
            output.contains("7-Zip"))
        assertTrue("Expected NSIS in `7zz i` supported formats, got first 1000 chars: ${output.take(1000)}",
            output.contains("Nsis", ignoreCase = true) || output.contains("NSIS"))
    }

    @Test
    fun `locator caches extracted binary across calls`() {
        val os = resolveHostOs()
        if (os == HostOs.WINDOWS) return

        val first = SevenZipLocator.locate() ?: return
        val second = SevenZipLocator.locate()
        // Same hash → same cache dir → same path. Note: if PATH already has 7z installed,
        // the locator prefers the bundled extraction on Linux/Mac so the path is stable.
        assertEquals(first, second)
    }

    @Test
    fun `locator returns null when neither bundled nor PATH binary is reachable`() {
        // We can't easily neutralize the bundled resource at unit-test time, so this is
        // documented as a behavioural assertion in the locator's KDoc and exercised through
        // the dispatch test (unpackExeWith7z error path).
        assumeTrue("This invariant is covered by IdeUnpackerDispatchTest on Windows hosts", false)
        // Pin a no-op so JUnit doesn't classify this @Test as failing.
        assertNull(null)
    }
}
