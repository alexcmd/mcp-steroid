/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Returns the JetBrains products-API download key for the given OS / architecture combo.
 *
 * When [preferWindowsZip] is `true`, Windows downloads resolve to the `windowsZip` /
 * `windowsZipARM64` variant. That avoids the NSIS .exe → 7zip extraction path entirely
 * for IDEs that publish a plain `.win.zip` (Community editions, all 2024.x+ IDEs).
 * Fallback to the .exe download key happens at lookup time if `windowsZip` is missing.
 *
 * @see <a href="https://data.services.jetbrains.com/products">JetBrains Products API</a>
 */
fun resolveDownloadKey(
    os: HostOs,
    architecture: HostArchitecture,
    preferWindowsZip: Boolean = false,
): String = when (os) {
    HostOs.LINUX -> if (architecture.isArmArch) "linuxARM64" else "linux"
    HostOs.MAC -> if (architecture.isArmArch) "macM1" else "mac"
    HostOs.WINDOWS -> when {
        preferWindowsZip && architecture.isArmArch -> "windowsZipARM64"
        preferWindowsZip -> "windowsZip"
        architecture.isArmArch -> "windowsARM64"
        else -> "windows"
    }
}

/**
 * Returns the preferred + fallback download keys for the given OS / architecture.
 *
 * Used by [resolveArchiveUrl] when [preferWindowsZip] is on: if the products API
 * doesn't list the zip variant for this combo (e.g. older releases), the resolver
 * silently falls back to the .exe.
 */
private fun downloadKeyCandidates(
    os: HostOs,
    architecture: HostArchitecture,
    preferWindowsZip: Boolean,
): List<String> {
    if (os != HostOs.WINDOWS || !preferWindowsZip) {
        return listOf(resolveDownloadKey(os, architecture, preferWindowsZip = preferWindowsZip))
    }
    // Windows + preferZip: try zip first, then exe.
    return listOf(
        resolveDownloadKey(os, architecture, preferWindowsZip = true),
        resolveDownloadKey(os, architecture, preferWindowsZip = false),
    )
}

/**
 * Resolves the download URL for the latest IDE archive from the public products API.
 *
 * @param product the IDE product to look up
 * @param channel the release channel (stable or EAP)
 * @param os the target operating system (default: auto-detected)
 * @param architecture the host architecture for platform-specific archive selection
 * @param preferWindowsZip on Windows, prefer the `.win.zip` (no 7zip needed) over the `.exe` installer
 * @return the direct download URL for the archive
 */
fun resolveArchiveUrl(
    product: IdeProduct,
    channel: IdeChannel,
    os: HostOs = resolveHostOs(),
    architecture: HostArchitecture = resolveHostArchitecture(),
    preferWindowsZip: Boolean = true,
): String {
    // Android Studio is a Google product and lives on a different feed.
    if (product === IdeProduct.AndroidStudio) {
        return resolveAndroidStudioArchiveUrl(channel, os, architecture, preferWindowsZip)
    }

    val releaseType = URLEncoder.encode(channel.apiValue, StandardCharsets.UTF_8)
    val url = "https://data.services.jetbrains.com/products?code=${product.code}&release.type=$releaseType"

    println("[IDE-DOWNLOAD] Fetching products info from $url")
    val payload = readUrlText(url)

    val json = Json { ignoreUnknownKeys = true }
    val products = json.parseToJsonElement(payload).jsonArray

    val matchingProduct = products
        .filterIsInstance<JsonObject>()
        .firstOrNull { obj -> (obj["code"] as? JsonPrimitive)?.content == product.code }
        ?: error("Products response does not contain '${product.code}' entry")

    val releases = (matchingProduct["releases"] as? JsonArray) ?: JsonArray(emptyList())
    val candidates = downloadKeyCandidates(os, architecture, preferWindowsZip)

    for (downloadKey in candidates) {
        val release = releases
            .filterIsInstance<JsonObject>()
            .firstOrNull { candidate ->
                val type = (candidate["type"] as? JsonPrimitive)?.content
                val version = (candidate["version"] as? JsonPrimitive)?.content
                val build = (candidate["build"] as? JsonPrimitive)?.content
                val downloads = candidate["downloads"] as? JsonObject
                val link = (downloads?.get(downloadKey) as? JsonObject)?.get("link")?.let { (it as? JsonPrimitive)?.content }

                type.equals(channel.apiValue, ignoreCase = true) &&
                        !version.isNullOrBlank() &&
                        !build.isNullOrBlank() &&
                        !link.isNullOrBlank()
            }
            ?: continue

        val downloads = release["downloads"] as? JsonObject ?: continue
        val platformDownload = downloads[downloadKey] as? JsonObject ?: continue
        val link = (platformDownload["link"] as? JsonPrimitive)?.content ?: continue
        return link
    }

    error(
        "Unable to resolve latest '${channel.apiValue}' release for product '${product.code}' " +
            "(tried download keys ${candidates.joinToString()}) from $url"
    )
}

internal fun readUrlText(url: String): String {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/json")
    }
    try {
        val statusCode = connection.responseCode
        val body = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (statusCode !in 200..299) {
            error("Failed to fetch from $url. HTTP $statusCode\n$body")
        }
        return body
    } finally {
        connection.disconnect()
    }
}
