package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Schema-versioned JSON document written to `~/.<pid>.mcp-steroid` by every
 * IDE that runs the MCP Steroid plugin. Discovered by external monitors
 * (npx-kt, the npm-distributed npx proxy) to learn where the IDE's MCP
 * server is reachable.
 *
 * Forward/backward compatibility: readers MUST decode with
 * [PidMarkerJson], which is configured to ignore unknown JSON keys and
 * tolerate missing optional fields. New optional fields ship with a default
 * so old writers remain compatible.
 */
// `port` mirrors the TCP port already embedded in [PidMarker.mcpUrl], surfaced
// as a typed field so consumers don't need to parse the URL. `token` is the
// MCP server's bearer auth token — clients SHOULD send
// `Authorization: Bearer <token>` on every request to the IntelliJ-hosted
// server. Both default to safe sentinels (0 / "") so older writers remain
// decodable.
@Serializable
data class PidMarker(
    val schema: Int = SCHEMA_VERSION,
    val pid: Long,
    val mcpUrl: String,
    val port: Int = 0,
    val token: String = "",
    val ide: IdeInfo,
    val plugin: PluginInfo,
    val createdAt: String,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1

        /** Filename pattern: `.<pid>.mcp-steroid` (no extension change vs. legacy text format). */
        fun fileNameFor(pid: Long): String = ".$pid.mcp-steroid"

        /** Parses the pid out of a marker filename, or `null` if the name doesn't match. */
        fun pidFromFileName(fileName: String): Long? {
            val match = FILE_NAME_REGEX.matchEntire(fileName) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

        private val FILE_NAME_REGEX = Regex("""^\.(\d+)\.mcp-steroid$""")
    }
}

/**
 * Tolerant JSON codec for [PidMarker]. `ignoreUnknownKeys = true` is the
 * forward-compat hook; pretty-print keeps the file useful when a human
 * `cat`s it.
 */
object PidMarkerJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(marker: PidMarker): String = json.encodeToString(PidMarker.serializer(), marker)

    fun decode(text: String): PidMarker = json.decodeFromString(PidMarker.serializer(), text)

    /** Best-effort decode for diagnostic use (returns the raw object on failure to deserialize the typed shape). */
    fun decodeRaw(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject
}
