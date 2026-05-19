/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.attic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class SseEvent(
    val type: String,
    val data: JsonObject
)

/** Line-by-line SSE parser — call feed() for each line read from the stream. */
class SseStreamParser {
    private val pendingLines = mutableListOf<String>()

    /** Returns a complete SseEvent when a blank line terminates a block, null otherwise. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (pendingLines.isNotEmpty()) {
                val event = parseSseBlock(pendingLines.toList())
                pendingLines.clear()
                return event
            }
            return null
        }
        pendingLines += line
        return null
    }

    /** Flush any remaining buffered lines as a final event (e.g. on stream end). */
    fun flush(): SseEvent? {
        if (pendingLines.isEmpty()) return null
        val event = parseSseBlock(pendingLines.toList())
        pendingLines.clear()
        return event
    }
}

fun parseSseBlock(lines: List<String>): SseEvent? {
    var type: String? = null
    val dataLines = mutableListOf<String>()

    for (rawLine in lines) {
        val line = rawLine.trimEnd()
        if (line.isEmpty()) continue
        when {
            line.startsWith("event:") -> type = line.removePrefix("event:").trim()
            line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
        }
    }

    if (dataLines.isEmpty()) return null

    val dataText = dataLines.joinToString("\n")
    val data: JsonObject = try {
        Json.parseToJsonElement(dataText).jsonObject
    } catch (e: Exception) {
        buildJsonObject { put("message", dataText) }
    }

    val resolvedType = type
        ?: data["type"]?.jsonPrimitive?.content
        ?: "message"

    return SseEvent(type = resolvedType, data = data)
}
