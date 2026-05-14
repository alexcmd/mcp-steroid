/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Locates a `7z` binary capable of extracting NSIS installers (Windows IDE `.exe`).
 *
 * **Priority order:**
 *  1. JAR-bundled `7zz` 23.01 (Linux x64 / Linux arm64 / macOS universal). Extracted on
 *     first use to `~/.cache/mcp-steroid/7z/<sha256>/7zz` and reused thereafter.
 *  2. `7z` / `7za` / `7zz` on `PATH` — used on Windows hosts (we don't bundle a Windows
 *     7-Zip; the upstream `7zr.exe` doesn't support NSIS and the full installer is
 *     non-trivial to package).
 *
 * Either binary supports the NSIS format (`7z i` includes it on 7-Zip ≥ 9.20 / 7zz 23.01).
 *
 * License: 7-Zip is **LGPL v2.1+** (https://www.7-zip.org/license.txt). The license text
 * ships alongside the binary at `7z/License.txt` in the JAR. See `THIRD_PARTY_NOTICES.md`.
 */
object SevenZipLocator {
    private val cacheRoot: File by lazy {
        val home = System.getProperty("user.home") ?: error("user.home is not set")
        File(home, ".cache/mcp-steroid/7z").apply { mkdirs() }
    }

    /**
     * Returns an absolute path to a runnable `7z` executable that supports NSIS, or `null`
     * when no candidate is available on this host (the caller surfaces a clear error).
     */
    fun locate(
        os: HostOs = resolveHostOs(),
        architecture: HostArchitecture = resolveHostArchitecture(),
    ): String? {
        // 1) Bundled 7zz for Linux + Mac hosts.
        extractBundled(os, architecture)?.let { return it.absolutePath }

        // 2) PATH lookup — primary path on Windows hosts and a graceful fallback on others.
        return locateOnPath(os)
    }

    private fun extractBundled(os: HostOs, architecture: HostArchitecture): File? {
        val resourcePath = bundledResourcePath(os, architecture) ?: return null
        val classLoader = SevenZipLocator::class.java.classLoader

        val bytes = classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: return null
        val licenseBytes = classLoader.getResourceAsStream(resourcePath.substringBeforeLast('/') + "/License.txt")
            ?.use { it.readBytes() }
            ?: ByteArray(0)

        val digest = sha256(bytes).take(16) // 16 hex chars is enough to namespace cached copies
        val targetDir = File(cacheRoot, digest)
        val binaryName = resourcePath.substringAfterLast('/')
        val targetBinary = File(targetDir, binaryName)
        if (targetBinary.isFile && targetBinary.canExecute() && targetBinary.length() == bytes.size.toLong()) {
            return targetBinary
        }

        targetDir.mkdirs()
        val tmpFile = File(targetDir, "$binaryName.tmp")
        tmpFile.outputStream().use { it.write(bytes) }
        Files.move(tmpFile.toPath(), targetBinary.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        targetBinary.setExecutable(true, false)

        if (licenseBytes.isNotEmpty()) {
            File(targetDir, "License.txt").writeBytes(licenseBytes)
        }

        return targetBinary
    }

    private fun bundledResourcePath(os: HostOs, architecture: HostArchitecture): String? = when (os) {
        HostOs.LINUX -> if (architecture.isArmArch) "7z/linux-arm64/7zz" else "7z/linux-x64/7zz"
        HostOs.MAC -> "7z/mac/7zz" // universal binary
        HostOs.WINDOWS -> null // see KDoc — no bundled Windows binary today
    }

    private fun locateOnPath(os: HostOs): String? {
        val path = System.getenv("PATH") ?: return null
        val sep = if (os == HostOs.WINDOWS) ';' else ':'
        val candidates = if (os == HostOs.WINDOWS) listOf("7z.exe", "7za.exe") else listOf("7zz", "7z", "7za")
        for (dir in path.split(sep)) {
            for (name in candidates) {
                val candidate = File(dir, name)
                if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            }
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
