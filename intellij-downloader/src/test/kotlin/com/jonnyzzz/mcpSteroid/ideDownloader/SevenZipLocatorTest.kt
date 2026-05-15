/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SevenZipLocatorTest {

    @Test
    fun `Windows 7-Zip bundle resources are present and parseable`() {
        assertPeResource("7z/win-x64/7z.exe")
        assertPeResource("7z/win-x64/7z.dll")

        val platformLicense = readResource("7z/win-x64/License.txt").decodeToString()
        assertTrue("Expected 7-Zip license text in win-x64 copy", platformLicense.contains("7-Zip"))

        val sharedLicense = readResource("7z/License.txt").decodeToString()
        assertTrue("Expected shared 7-Zip license text", sharedLicense.contains("7-Zip"))
    }

    @Test
    fun `Windows locator extracts full bundle tuple and reuses cache`() {
        val first = SevenZipLocator.locate(os = HostOs.WINDOWS, architecture = HostArchitecture.X86_64)
        assertNotNull("Expected bundled Windows 7z.exe to resolve from classpath", first)

        val binary = File(first!!)
        assertEquals("7z.exe", binary.name)
        assertTrue("Located 7z.exe should exist in cache: $binary", binary.isFile)
        assertTrue("Located 7z.exe should be executable in cache: $binary", binary.canExecute())

        val cacheDir = binary.parentFile
        listOf("7z.exe", "7z.dll", "License.txt").forEach { fileName ->
            assertTrue("Expected cached bundled payload file $fileName in $cacheDir",
                File(cacheDir, fileName).isFile)
        }

        val second = SevenZipLocator.locate(os = HostOs.WINDOWS, architecture = HostArchitecture.X86_64)
        assertEquals("Same 7z.exe hash should reuse the same cache path", first, second)
    }

    @Test
    fun `Linux and macOS locator use PATH fallback when available`() {
        listOf(HostOs.LINUX, HostOs.MAC).forEach { os ->
            val located = SevenZipLocator.locate(os = os, architecture = HostArchitecture.X86_64)
            if (located != null) {
                val binary = File(located)
                assertTrue("PATH-located 7z candidate should exist: $binary", binary.isFile)
                assertTrue("PATH-located 7z candidate should be executable: $binary", binary.canExecute())
                assertTrue("Unexpected PATH-located 7z candidate name: ${binary.name}",
                    binary.name in setOf("7zz", "7z", "7za"))
            }
        }
    }

    private fun assertPeResource(path: String) {
        val bytes = readResource(path)
        assertTrue("Expected non-empty PE resource at $path", bytes.size > 1024)
        assertEquals("Expected MZ header byte 0 for $path", 'M'.code.toByte(), bytes[0])
        assertEquals("Expected MZ header byte 1 for $path", 'Z'.code.toByte(), bytes[1])
    }

    private fun readResource(path: String): ByteArray {
        val stream = SevenZipLocatorTest::class.java.classLoader.getResourceAsStream(path)
        assertNotNull("Expected resource at $path", stream)
        return stream!!.use { it.readBytes() }
    }
}
