/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Resolves the download URL and file name for an [IdeDistribution],
 * then downloads the archive (with caching) to the specified directory.
 *
 * @param downloadDir the directory to store downloaded archives
 * @param os the target operating system (default: auto-detected)
 * @param preferWindowsZip on Windows, prefer the `.win.zip` over the `.exe` installer
 *  (avoids the need for 7zip on the host). Defaults to true.
 * @return the local file containing the IDE archive
 */
fun IdeDistribution.resolveAndDownload(
    downloadDir: File,
    os: HostOs = resolveHostOs(),
    preferWindowsZip: Boolean = true,
): File {
    requirePaidConsent()
    downloadDir.mkdirs()

    val (url, fileName) = resolveUrlAndFileName(os, preferWindowsZip)
    val destFile = File(downloadDir, fileName)

    if (destFile.exists()) {
        println("[IDE-DOWNLOAD] Using cached archive: $destFile")
        return destFile
    }

    println("[IDE-DOWNLOAD] Downloading $url -> $destFile")
    downloadFile(url, destFile)
    return destFile
}

private fun IdeDistribution.resolveUrlAndFileName(
    os: HostOs,
    preferWindowsZip: Boolean,
): Pair<String, String> {
    return when (this) {
        is IdeDistribution.FromUrl -> {
            val resolvedName = fileName ?: archiveFileNameFromUrl(url, "${product.id}.tar.gz")
            url to resolvedName
        }
        is IdeDistribution.Latest -> {
            val resolved = resolveArchive(product, channel, os, preferWindowsZip = preferWindowsZip)
            val resolvedUrl = resolved.url
            val arch = resolveHostArchitecture()
            val fallbackName = if (arch.isArmArch) "${product.id}-${channel.name.lowercase()}-arm.tar.gz"
                               else "${product.id}-${channel.name.lowercase()}-x86.tar.gz"
            val resolvedName = archiveFileNameFromUrl(resolvedUrl, fallbackName)
            resolvedUrl to resolvedName
        }
    }
}

private fun archiveFileNameFromUrl(url: String, fallbackFileName: String): String {
    val fileName = try { URI(url).path.substringAfterLast('/') } catch (_: Exception) { null }
    return fileName?.takeIf { it.isNotBlank() } ?: fallbackFileName
}

private fun downloadFile(url: String, dest: File) {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 30_000
        readTimeout = 15 * 60_000
        instanceFollowRedirects = true
    }
    try {
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            error("Failed to download $url. HTTP $statusCode")
        }
        val totalBytes = connection.contentLengthLong
        var downloaded = 0L
        var lastPrinted = 0L

        val tempFile = File(dest.parent, "${dest.name}.tmp")
        try {
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastPrinted >= 5_000) {
                            val progress = if (totalBytes > 0) " (${downloaded * 100 / totalBytes}%)" else ""
                            println("[IDE-DOWNLOAD] Progress: ${downloaded / 1024 / 1024} MB$progress")
                            lastPrinted = now
                        }
                    }
                }
            }
            tempFile.renameTo(dest)
            println("[IDE-DOWNLOAD] Downloaded ${downloaded / 1024 / 1024} MB to $dest")
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    } finally {
        connection.disconnect()
    }
}
