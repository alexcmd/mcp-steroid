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
 *  1. JAR-bundled `7z.exe` + `7z.dll` + `License.txt` from 7-Zip 23.01 on
 *     Windows hosts only. The fixed tuple is copied on first use to
 *     `~/.cache/mcp-steroid/7z/<sha256>/` and reused thereafter; the cache key is
 *     derived from the primary `7z.exe` bytes.
 *  2. `7z` / `7za` / `7zz` on `PATH`. This is the primary path on Linux and
 *     macOS hosts, and the Windows fallback when bundled resources are unavailable
 *     (for example in narrow unit-test classpaths).
 *
 * 7-Zip 23.01 statically links NSIS and LZMA2 support into `7z.dll`; we no
 * longer ship separate `Formats/Nsis.dll` or `Codecs/Lzma2.dll` plugin files.
 *
 * License: 7-Zip is **LGPL v2.1+** (https://www.7-zip.org/license.txt). The license text
 * ships alongside the binary in the installDist / JAR. See `THIRD_PARTY_NOTICES.md`.
 */
object SevenZipLocator {
    private data class BundledPayload(
        val primaryName: String,
        val fileNames: List<String>,
        val executableNames: Set<String>,
    )

    private val windowsPayload = BundledPayload(
        primaryName = "7z.exe",
        fileNames = listOf("7z.exe", "7z.dll", "License.txt"),
        executableNames = setOf("7z.exe"),
    )

    // Compatibility with older unpacked npx-kt trees and existing tests. Current builds
    // no longer ship this payload; Linux/macOS hosts use PATH unless such a legacy tree
    // is supplied explicitly by the embedding distribution.
    private val legacyUnixPayload = BundledPayload(
        primaryName = "7zz",
        fileNames = listOf("7zz", "License.txt"),
        executableNames = setOf("7zz"),
    )

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

        // 2) PATH lookup — primary on Linux/macOS and fallback on Windows.
        return locateOnPath(os)
    }

    private fun extractBundled(os: HostOs, architecture: HostArchitecture): File? {
        val sevenZipDir = npxKtSevenZipDirOrNull()
        if (sevenZipDir != null) {
            val source = bundledFilePath(sevenZipDir, os, architecture)
            if (source != null) {
                copyBundledFileToCache(source, payloadForPrimary(source.fileName.toString()))?.let { return it }
            }
        }

        return extractBundledResource(os, architecture)
    }

    private fun copyBundledFileToCache(source: Path, payload: BundledPayload): File? {
        if (!Files.isRegularFile(source)) return null
        val sourceDir = source.parent ?: return null
        val sourceFiles = payload.fileNames.associateWith { fileName ->
            sourceDir.resolve(fileName).takeIf { Files.isRegularFile(it) } ?: return null
        }
        val primaryBytes = Files.readAllBytes(sourceFiles.getValue(payload.primaryName))
        val digest = sha256(primaryBytes).take(16) // 16 hex chars is enough to namespace cached copies
        val targetDir = File(cacheRoot, digest)
        val targetBinary = File(targetDir, payload.primaryName)
        if (isCompleteCachedPayload(targetDir, payload, primaryBytes.size)) {
            return targetBinary
        }

        targetDir.mkdirs()
        sourceFiles.forEach { (fileName, sourceFile) ->
            val target = targetDir.toPath().resolve(fileName)
            val tmpFile = targetDir.toPath().resolve("$fileName.tmp")
            Files.copy(sourceFile, tmpFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            if (fileName in payload.executableNames) {
                target.toFile().setExecutable(true, false)
            }
        }

        return targetBinary
    }

    private fun extractBundledResource(os: HostOs, architecture: HostArchitecture): File? {
        val resourcePath = bundledResourcePath(os, architecture) ?: return null
        val resourceDir = resourcePath.substringBeforeLast('/')
        val classLoader = SevenZipLocator::class.java.classLoader
        val resourceBytes = windowsPayload.fileNames.associateWith { fileName ->
            classLoader.getResourceAsStream("$resourceDir/$fileName")?.use { it.readBytes() } ?: return null
        }

        val primaryBytes = resourceBytes.getValue(windowsPayload.primaryName)
        val digest = sha256(primaryBytes).take(16) // 16 hex chars is enough to namespace cached copies
        val targetDir = File(cacheRoot, digest)
        val targetBinary = File(targetDir, windowsPayload.primaryName)
        if (isCompleteCachedPayload(targetDir, windowsPayload, primaryBytes.size)) {
            return targetBinary
        }

        targetDir.mkdirs()
        resourceBytes.forEach { (fileName, bytes) ->
            val target = File(targetDir, fileName)
            val tmpFile = File(targetDir, "$fileName.tmp")
            tmpFile.outputStream().use { it.write(bytes) }
            Files.move(tmpFile.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            if (fileName in windowsPayload.executableNames) {
                target.setExecutable(true, false)
            }
        }

        return targetBinary
    }

    private fun isCompleteCachedPayload(targetDir: File, payload: BundledPayload, primarySize: Int): Boolean {
        val targetBinary = File(targetDir, payload.primaryName)
        if (!targetBinary.isFile || targetBinary.length() != primarySize.toLong()) return false
        if (payload.primaryName in payload.executableNames && !targetBinary.canExecute()) return false
        return payload.fileNames.all { fileName -> File(targetDir, fileName).isFile }
    }

    private fun bundledFilePath(sevenZipDir: Path, os: HostOs, architecture: HostArchitecture): Path? = when (os) {
        HostOs.LINUX -> sevenZipDir.resolve(if (architecture.isArmArch) "linux-arm64/7zz" else "linux-x64/7zz")
        HostOs.MAC -> sevenZipDir.resolve("mac/7zz")
        HostOs.WINDOWS -> sevenZipDir.resolve("win-x64/7z.exe")
    }

    private fun payloadForPrimary(primaryName: String): BundledPayload = when (primaryName) {
        windowsPayload.primaryName -> windowsPayload
        legacyUnixPayload.primaryName -> legacyUnixPayload
        else -> BundledPayload(primaryName, listOf(primaryName), setOf(primaryName))
    }

    private fun bundledResourcePath(os: HostOs, architecture: HostArchitecture): String? = when (os) {
        HostOs.WINDOWS -> "7z/win-x64/7z.exe"
        HostOs.LINUX,
        HostOs.MAC -> null
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
