/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val ideReleaseLookupLog = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeReleaseLookup")

data class IdeArchiveResolution(
    val product: IdeProduct,
    val channel: IdeChannel,
    val version: String,
    val build: String,
    val url: String,
    val downloadKey: String,
    val releaseDate: String? = null,
)

/**
 * Returns the JetBrains products-API download key for the given OS / architecture combo.
 *
 * @see <a href="https://data.services.jetbrains.com/products">JetBrains Products API</a>
 */
fun resolveDownloadKey(
    os: HostOs,
    architecture: HostArchitecture,
): String = when (os) {
    HostOs.LINUX -> if (architecture.isArmArch) "linuxARM64" else "linux"
    HostOs.MAC -> if (architecture.isArmArch) "macM1" else "mac"
    HostOs.WINDOWS -> if (architecture.isArmArch) "windowsARM64" else "windows"
}

/**
 * Resolves the download URL for the latest IDE archive from the public products API.
 *
 * @param product the IDE product to look up
 * @param channel the release channel (stable or EAP)
 * @param os the target operating system (default: auto-detected)
 * @param architecture the host architecture for platform-specific archive selection
 * @return the direct download URL for the archive
 */
fun resolveArchiveUrl(
    product: IdeProduct,
    channel: IdeChannel,
    os: HostOs = resolveHostOs(),
    architecture: HostArchitecture = resolveHostArchitecture(),
): String {
    return resolveArchive(product, channel, os, architecture).url
}

fun resolveArchive(
    product: IdeProduct,
    channel: IdeChannel,
    os: HostOs = resolveHostOs(),
    architecture: HostArchitecture = resolveHostArchitecture(),
    version: String? = null,
): IdeArchiveResolution {
    // Android Studio is a Google product and lives on a different feed.
    if (product === IdeProduct.AndroidStudio) {
        return resolveAndroidStudioArchive(channel, os, architecture, version)
    }

    // IDEA Community stable trails Ultimate. The github.com/JetBrains/intellij-community
    // `idea/2026.1.*` tags are source-only; downloads.jetbrains.com does NOT host
    // `ideaIC-2026.1.*` binaries today. The products API correctly reports 2025.3
    // as latest stable for code=IIC. Don't try to synthesise a download URL —
    // trust the API.
    val releaseType = URLEncoder.encode(channel.apiValue, StandardCharsets.UTF_8)
    val url = "https://data.services.jetbrains.com/products?code=${product.code}&release.type=$releaseType"

    logFetchingProductsInfo(url)
    val payload = readUrlText(url)

    val json = Json { ignoreUnknownKeys = true }
    val products = json.parseToJsonElement(payload).jsonArray

    val matchingProduct = products
        .filterIsInstance<JsonObject>()
        .firstOrNull { obj -> (obj["code"] as? JsonPrimitive)?.content == product.code }
        ?: error("Products response does not contain '${product.code}' entry")

    val releases = (matchingProduct["releases"] as? JsonArray) ?: JsonArray(emptyList())
    val downloadKey = resolveDownloadKey(os, architecture)
    val wantedVersion = version?.takeIf { it.isNotBlank() }

    for (release in releases.filterIsInstance<JsonObject>()) {
        val type = (release["type"] as? JsonPrimitive)?.content
        val releaseVersion = (release["version"] as? JsonPrimitive)?.content
        val build = (release["build"] as? JsonPrimitive)?.content
        val releaseDate = (release["date"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (!type.equals(channel.apiValue, ignoreCase = true)) continue
        if (releaseVersion.isNullOrBlank() || build.isNullOrBlank()) continue
        if (wantedVersion != null && wantedVersion != releaseVersion && wantedVersion != build) continue

        val downloads = release["downloads"] as? JsonObject ?: continue
        val platformDownload = downloads[downloadKey] as? JsonObject ?: continue
        val link = (platformDownload["link"] as? JsonPrimitive)?.content ?: continue
        if (link.isBlank()) continue
        return IdeArchiveResolution(
            product = product,
            channel = channel,
            version = releaseVersion,
            build = build,
            url = link,
            downloadKey = downloadKey,
            releaseDate = releaseDate,
        )
    }

    val versionMessage = if (wantedVersion == null) "latest" else "version '$wantedVersion'"
    error(
        "Unable to resolve $versionMessage '${channel.apiValue}' release for product '${product.code}' " +
            "(tried download key $downloadKey) from $url"
    )
}

internal fun logFetchingProductsInfo(url: String) {
    ideReleaseLookupLog.debug("[IDE-DOWNLOAD] Fetching products info from {}", url)
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
