/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Locates a `7z` binary capable of extracting NSIS installers (Windows IDE `.exe`).
 *
 * **Priority order:**
 *  1. `npx-kt` installDist-bundled `7z/` binaries, when this library is running inside
 *     the proxy distribution. Copied on first use to `~/.cache/mcp-steroid/7z/<sha256>/`
 *     and reused thereafter.
 *  2. Legacy JAR-bundled `7zz` resources for the standalone intellij-downloader CLI.
 *  3. `7z` / `7za` / `7zz` on `PATH`.
 *
 * Either binary supports the NSIS format (`7z i` includes it on 7-Zip ≥ 9.20 / 7zz 23.01).
 *
 * License: 7-Zip is **LGPL v2.1+** (https://www.7-zip.org/license.txt). The license text
 * ships alongside the binary in the installDist / JAR. See `THIRD_PARTY_NOTICES.md`.
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
        // 1) Bundled binaries are preferred when present.
        extractBundled(os, architecture)?.let { return it.absolutePath }

        // 2) PATH lookup — graceful fallback when the distribution has not shipped this host yet.
        return locateOnPath(os)
    }

    private fun extractBundled(os: HostOs, architecture: HostArchitecture): File? {
        val sevenZipDir = npxKtSevenZipDirOrNull()
        if (sevenZipDir != null) {
            val source = bundledFilePath(sevenZipDir, os, architecture)
            return copyBundledFileToCache(source)
        }

        return extractBundledResource(os, architecture)
    }

    private fun copyBundledFileToCache(source: Path): File? {
        if (!Files.isRegularFile(source)) return null

        val bytes = Files.readAllBytes(source)
        val digest = sha256(bytes).take(16) // 16 hex chars is enough to namespace cached copies
        val targetDir = File(cacheRoot, digest)
        val binaryName = source.fileName.toString()
        val targetBinary = File(targetDir, binaryName)
        if (targetBinary.isFile && targetBinary.canExecute() && targetBinary.length() == bytes.size.toLong()) {
            return targetBinary
        }

        targetDir.mkdirs()
        val tmpFile = targetDir.toPath().resolve("$binaryName.tmp")
        Files.copy(source, tmpFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        Files.move(tmpFile, targetBinary.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        targetBinary.setExecutable(true, false)

        val license = source.parent?.resolve("License.txt")
        if (license != null && Files.isRegularFile(license)) {
            Files.copy(license, targetDir.toPath().resolve("License.txt"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }

        return targetBinary
    }

    private fun extractBundledResource(os: HostOs, architecture: HostArchitecture): File? {
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

    private fun bundledFilePath(sevenZipDir: Path, os: HostOs, architecture: HostArchitecture): Path = when (os) {
        HostOs.LINUX -> sevenZipDir.resolve(if (architecture.isArmArch) "linux-arm64/7zz" else "linux-x64/7zz")
        HostOs.MAC -> sevenZipDir.resolve("mac/7zz") // universal binary
        HostOs.WINDOWS -> sevenZipDir.resolve(if (architecture.isArmArch) "windows-arm64/7z.exe" else "windows-x64/7z.exe")
    }

    private fun bundledResourcePath(os: HostOs, architecture: HostArchitecture): String? = when (os) {
        HostOs.LINUX -> if (architecture.isArmArch) "7z/linux-arm64/7zz" else "7z/linux-x64/7zz"
        HostOs.MAC -> "7z/mac/7zz" // universal binary
        HostOs.WINDOWS -> null // standalone intellij-downloader still does not bundle Windows 7z
    }

    private fun npxKtSevenZipDirOrNull(): Path? {
        val rootClass = try {
            Class.forName("com.jonnyzzz.mcpSteroid.proxy.NpxKtRoot")
        } catch (e: ClassNotFoundException) {
            return null
        }

        val instance = rootClass.getField("INSTANCE").get(null)
        return try {
            rootClass.getMethod("sevenZipDir").invoke(instance) as Path
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Cannot resolve npx-kt 7z directory", cause)
            }
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("Cannot invoke com.jonnyzzz.mcpSteroid.proxy.NpxKtRoot.sevenZipDir()", e)
        }
    }

    private fun locateOnPath(os: HostOs): String? {
        val path = System.getenv("PATH") ?: return null
        val sep = if (os == HostOs.WINDOWS) ';' else ':'
        val candidates = if (os == HostOs.WINDOWS) listOf("7z.exe", "7za.exe", "7zz.exe") else listOf("7zz", "7z", "7za")
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
