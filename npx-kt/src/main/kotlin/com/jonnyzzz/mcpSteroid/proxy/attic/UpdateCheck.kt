/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.attic

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun buildVersionEndpointUrl(registry: ServerRegistry): String {
    val freshest = registry.listOnlineServersByFreshness().firstOrNull()
    val ideBuild = freshest?.metadata?.ide?.build
    return if (ideBuild != null) {
        "https://mcp-steroid.jonnyzzz.com/version.json?intellij-version=${java.net.URLEncoder.encode(ideBuild, "UTF-8")}"
    } else {
        "https://mcp-steroid.jonnyzzz.com/version.json"
    }
}

private fun buildUpdateUserAgent(config: ProxyConfig, registry: ServerRegistry): String {
    val freshest = registry.listOnlineServersByFreshness().firstOrNull()
    val ideBuild = freshest?.metadata?.ide?.build
    val proxyVersion = config.version ?: "0.1.0"
    return if (ideBuild != null) {
        "MCP-Steroid-Proxy/$proxyVersion (IntelliJ/$ideBuild)"
    } else {
        "MCP-Steroid-Proxy/$proxyVersion"
    }
}

suspend fun fetchRemoteVersionBase(registry: ServerRegistry, config: ProxyConfig): String? {
    val url = buildVersionEndpointUrl(registry)
    val timeoutMs = config.updates.requestTimeoutMs
    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs
        }
    }
    return try {
        val response = client.get(url) {
            header("Accept", "application/json")
            header("User-Agent", buildUpdateUserAgent(config, registry))
        }
        if (!response.status.isSuccess()) return null
        val text = response.bodyAsText()
        val json = Json.parseToJsonElement(text).jsonObject
        val value = json["version-base"]?.jsonPrimitive?.content ?: return null
        extractBaseVersion(value)
    } catch (e: Exception) {
        null
    } finally {
        client.close()
    }
}

fun needsUpgradeByServerRule(currentVersion: String?, remoteVersionBase: String?): Boolean {
    val current = currentVersion?.trim() ?: return false
    if (current.isEmpty()) return false
    val remoteBase = extractBaseVersion(remoteVersionBase ?: "")
    if (remoteBase.isEmpty()) return false
    return !current.startsWith(remoteBase)
}

fun pickRecommendedVersion(remoteBase: String?, pluginBase: String?): String? {
    if (remoteBase != null && pluginBase != null) {
        return if (isVersionNewer(remoteBase, pluginBase)) remoteBase else pluginBase
    }
    return remoteBase ?: pluginBase
}

suspend fun buildUpgradeNotice(registry: ServerRegistry, config: ProxyConfig): String? {
    val currentVersion = config.version ?: return null
    val currentBase = extractBaseVersion(currentVersion)
    if (currentBase.isEmpty()) return null

    val remoteBase = fetchRemoteVersionBase(registry, config) ?: return null
    if (!needsUpgradeByServerRule(currentVersion, remoteBase)) return null

    return "[mcp-steroid-proxy] Upgrade recommended: current $currentBase, latest $remoteBase. Run: java -jar mcp-steroid-proxy.jar (update your installation)"
}
