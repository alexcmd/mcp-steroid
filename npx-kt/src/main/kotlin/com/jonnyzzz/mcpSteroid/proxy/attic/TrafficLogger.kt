/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.attic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate

class TrafficLogger(private val config: ProxyConfig) {
    private val enabled = config.trafficLog.enabled
    private val redactFields = config.trafficLog.redactFields.toSet()
    private val dir = config.cache.dir

    suspend fun log(record: TrafficRecord) {
        if (!enabled) return
        try {
            val logDir = File(dir, "logs")
            logDir.mkdirs()
            val date = LocalDate.now().toString()
            val file = File(logDir, "$date.jsonl")
            val payload = buildJsonObject {
                put("ts", record.ts)
                put("direction", record.direction)
                record.session?.let { put("session", it) }
                record.method?.let { put("method", it) }
                record.serverId?.let { put("serverId", it) }
                record.requestId?.let { put("requestId", it) }
                put("payload", redact(record.payload))
            }
            val line = Json.encodeToString(JsonObject.serializer(), payload) + "\n"
            file.appendText(line, Charsets.UTF_8)
        } catch (e: Exception) {
            // Ignore logging failures
        }
    }

    private fun redact(element: JsonElement?): JsonElement {
        if (element == null) return JsonNull
        return when (element) {
            is JsonObject -> JsonObject(element.entries.associate { (key, value) ->
                key to if (redactFields.contains(key)) JsonPrimitive("[redacted]") else redact(value)
            })
            is JsonArray -> JsonArray(element.map { redact(it) })
            else -> element
        }
    }
}

data class TrafficRecord(
    val ts: String,
    val direction: String,
    val payload: JsonElement? = null,
    val session: String? = null,
    val method: String? = null,
    val serverId: String? = null,
    val requestId: String? = null
)
