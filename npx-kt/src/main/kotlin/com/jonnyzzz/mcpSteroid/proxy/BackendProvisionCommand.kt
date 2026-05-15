/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintStream

internal fun runBackendProvisionListCommand(
    out: PrintStream,
    json: Boolean,
    targets: suspend (HttpClient) -> List<ProvisionTarget> = { httpClient -> detectProvisionTargets(httpClient) },
) {
    val rows = withProvisionHttpClient { httpClient ->
        runBlocking(Dispatchers.IO) {
            targets(httpClient)
        }
    }
    if (json) {
        renderBackendProvisionListJson(rows, out)
    } else {
        renderBackendProvisionListText(rows, out)
    }
}

internal fun runBackendProvisionCommand(
    out: PrintStream,
    homePaths: HomePaths,
    mode: CliMode.Backend.Provision,
    provision: suspend (HttpClient) -> ProvisionResult = { httpClient ->
        BackendManager(homePaths).provision(mode.id, httpClient)
    },
): Int {
    if (mode.json) {
        return runBackendActionJson(out, action = PROVISION_ACTION_ID, id = mode.id) {
            val result = withProvisionHttpClient { httpClient ->
                runBlocking(Dispatchers.IO) {
                    provision(httpClient)
                }
            }
            provisionResultJson(result)
        }
    }

    val result = withProvisionHttpClient { httpClient ->
        runBlocking(Dispatchers.IO) {
            provision(httpClient)
        }
    }
    if (result.alreadyProvisioned) {
        out.println("MCP Steroid plugin already provisioned at ${result.pluginPath}. Restart the IDE to load it if it is not loaded yet.")
    } else {
        out.println("MCP Steroid plugin installed at ${result.pluginPath}. Restart the IDE to load it.")
    }
    return 0
}

internal fun renderBackendProvisionListText(
    rows: List<ProvisionTarget>,
    out: PrintStream,
) {
    out.println("$BRAND_NAME v${loadProxyVersion()} — $BRAND_TAGLINE")
    out.println()
    if (rows.isEmpty()) {
        out.println("No port-discovered IDEs are available.")
        out.println()
        return
    }

    out.println("Port-discovered IDEs that can be provisioned:")
    out.println()
    val indexWidth = rows.size.toString().length + 2
    val idWidth = rows.maxOf { it.id.length }
    for ((index, row) in rows.withIndex()) {
        val indexLabel = "[${index + 1}]".padEnd(indexWidth)
        out.println("  $indexLabel ${row.id.padEnd(idWidth)}  ${portBackendDisplayName(row.ide)} (${portBackendLocatorLabel(row.ide)})")
        out.println("        run: ${row.command}")
    }
    out.println()
    out.println("Run:  $BRAND_NAME backend provision <id>")
    out.println()
}

internal fun renderBackendProvisionListJson(
    rows: List<ProvisionTarget>,
    out: PrintStream,
) {
    val payload = buildJsonObject {
        putToolJson()
        put("targets", buildJsonArray {
            for (row in rows) {
                add(provisionTargetJson(row))
            }
        })
    }
    out.println(backendPrettyJson.encodeToString(JsonObject.serializer(), payload))
}

internal fun provisionTargetJson(target: ProvisionTarget): JsonObject = buildJsonObject {
    put("id", target.id)
    put("displayName", portBackendDisplayName(target.ide))
    put("locator", portBackendLocatorLabel(target.ide))
    putJsonFields(portBackendIdentityJson(target.ide))
    put("actions", buildJsonArray {
        add(provisionActionJson(target.id))
    })
}

internal fun provisionActionJson(id: String): JsonObject = buildJsonObject {
    put("id", PROVISION_ACTION_ID)
    put("label", "Install MCP Steroid plugin")
    put("command", provisionCommand(id))
}

private fun provisionResultJson(result: ProvisionResult): JsonObject = buildJsonObject {
    putToolJson()
    put("action", PROVISION_ACTION_ID)
    put("id", result.id)
    put("status", if (result.alreadyProvisioned) "already_provisioned" else "installed")
    put("alreadyProvisioned", result.alreadyProvisioned)
    put("restartRequired", true)
    put("pluginPath", result.pluginPath.toString())
    put("pluginsPath", result.pluginsDir.toString())
    put("selector", result.selector)
    result.productCode?.let { put("productCode", it) }
    put("port", result.ide.port)
    put("baseUrl", result.ide.baseUrl)
    result.about.name?.let { put("name", it) }
    result.about.productName?.let { put("productName", it) }
    result.about.edition?.let { put("edition", it) }
    result.about.baselineVersion?.let { put("baselineVersion", it) }
    result.about.buildNumber?.let { put("buildNumber", it) }
}

private fun <T> withProvisionHttpClient(block: (HttpClient) -> T): T {
    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
    }
    try {
        return block(httpClient)
    } finally {
        httpClient.close()
    }
}
