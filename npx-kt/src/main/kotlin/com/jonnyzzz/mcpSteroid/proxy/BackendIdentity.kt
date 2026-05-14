/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIdeByPort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val BACKEND_TYPE_INTELLIJ = "intellij"

internal fun markerBackendDisplayName(ide: DiscoveredIde): String =
    "${ide.marker.ide.name} ${ide.marker.ide.version}".trim()

internal fun markerBackendLocatorLabel(ide: DiscoveredIde): String = "pid ${ide.pid}"

internal fun formatMarkerBackendIdentity(ide: DiscoveredIde): String =
    "${markerBackendDisplayName(ide)} (${markerBackendLocatorLabel(ide)})"

internal fun markerBackendIdentityJson(ide: DiscoveredIde): JsonObject = buildJsonObject {
    put("name", ide.marker.ide.name)
    put("version", ide.marker.ide.version)
    put("build", ide.marker.ide.build)
    put("pid", ide.pid)
    put("mcpUrl", ide.mcpUrl)
}

internal fun portBackendDisplayName(ide: DiscoveredIdeByPort): String =
    ide.productFullName ?: ide.productName ?: "(unknown JetBrains IDE)"

internal fun portBackendLocatorLabel(ide: DiscoveredIdeByPort): String = buildString {
    ide.buildNumber?.let { append("build ").append(it).append(", ") }
    append("port ").append(ide.port)
}

internal fun formatPortBackendIdentity(ide: DiscoveredIdeByPort): String =
    "${portBackendDisplayName(ide)} (${portBackendLocatorLabel(ide)})"

internal fun backendDisplayName(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> markerBackendDisplayName(row.ide)
    is BackendRow.FromPort -> portBackendDisplayName(row.ide)
    is BackendRow.FromManaged -> row.displayName
}

internal fun backendLocatorLabel(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> markerBackendLocatorLabel(row.ide) + if (row.managed) ", managed" else ""
    is BackendRow.FromPort -> portBackendLocatorLabel(row.ide) + if (row.managed) ", managed" else ""
    is BackendRow.FromManaged -> row.locatorLabel
}

internal fun portBackendIdentityJson(ide: DiscoveredIdeByPort): JsonObject = buildJsonObject {
    put("displayName", portBackendDisplayName(ide))
    put("port", ide.port)
    put("baseUrl", ide.baseUrl)
    ide.productName?.let { put("productName", it) }
    ide.productFullName?.let { put("productFullName", it) }
    ide.edition?.let { put("edition", it) }
    ide.baselineVersion?.let { put("baselineVersion", it) }
    ide.buildNumber?.let { put("buildNumber", it) }
    put("pluginInstalled", false)
}

internal fun JsonObjectBuilder.putJsonFields(fields: JsonObject) {
    for ((key, value) in fields) {
        put(key, value)
    }
}
