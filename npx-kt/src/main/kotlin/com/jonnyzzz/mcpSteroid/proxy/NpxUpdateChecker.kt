/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NpxVersionInfo(
    @kotlinx.serialization.SerialName("version-base")
    val versionBase: String,
)

suspend fun fetchVersionInfo(): NpxVersionInfo? {
    val json = Json { ignoreUnknownKeys = true }

    class HttpNpxVersionFetcher

    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    return try {
        val response = client.get("https://mcp-steroid.jonnyzzz.com/version.json") {
            header("Accept", "application/json")
            header("User-Agent", "devrig/${ProxyVersionMetadata.getProxyVersion()}")
        }
        if (!response.status.isSuccess()) return null
        return json.decodeFromString<NpxVersionInfo>(response.bodyAsText())
    } catch (e: Throwable) {
        logger<HttpNpxVersionFetcher>().debug("Update check failed. ${e.message}", e)
        null
    } finally {
        client.close()
    }
}

suspend fun checkForUpdates() {
    val remoteVersion = fetchVersionInfo() ?: return
    val currentVersion = ProxyVersionMetadata.getProxyVersion()
    if (currentVersion.startsWith(remoteVersion.versionBase)) return

    val newVersion = remoteVersion.versionBase
    val message = buildString {
        appendLine()
        appendLine("A new version of devrig is available: $newVersion (current: $currentVersion)")
        appendLine("Download update from: https://mcp-steroid.jonnyzzz.com/releases/")
        appendLine()
    }
    System.err.println(message)
}
