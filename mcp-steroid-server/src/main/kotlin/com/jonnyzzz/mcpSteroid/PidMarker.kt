package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * Schema-versioned JSON document written to
 * `~/.mcp-steroid/markers/<pid>.mcp-steroid` by every IDE that runs the
 * MCP Steroid plugin. Discovered by external monitors (npx-kt, the
 * npm-distributed npx proxy) to learn where the IDE's MCP server is
 * reachable.
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
    val intellijMcpServer: IntelliJMcpServerInfo? = null,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val MCP_STEROID_HOME_ENV: String = "MCP_STEROID_HOME"

        /**
         * Directory the plugin writes markers into.
         *
         * When [MCP_STEROID_HOME_ENV] is set, markers live directly under
         * `$MCP_STEROID_HOME/markers`. Otherwise they use the default managed
         * home root under the given [userHome]: `~/.mcp-steroid/markers`.
         */
        fun markerDirectory(
            userHome: Path,
            env: Map<String, String> = System.getenv(),
        ): Path = markerHomeDirectory(userHome, env).resolve("markers")

        /**
         * Managed MCP Steroid home root that owns marker storage.
         *
         * Exposed so proxy-side home path resolution can share the same
         * environment resolution as the plugin while still appending its own
         * managed subdirectories.
         */
        fun markerHomeDirectory(
            userHome: Path,
            env: Map<String, String> = System.getenv(),
        ): Path {
            val override = env[MCP_STEROID_HOME_ENV]?.takeIf { it.isNotBlank() }
            return if (override == null) {
                userHome.resolve(".mcp-steroid")
            } else {
                Path.of(override)
            }
        }

        /** Filename inside [markerDirectory] for a running IDE pid. */
        fun markerFileNameFor(pid: Long): String = "$pid.mcp-steroid"

        /** Legacy filename pattern: `.<pid>.mcp-steroid` in the user home root. */
        @Deprecated("use markerFileNameFor", ReplaceWith("markerFileNameFor(pid)"))
        fun fileNameFor(pid: Long): String = ".$pid.mcp-steroid"

        /**
         * Parses the pid out of a marker filename in either the new
         * `<pid>.mcp-steroid` layout or the legacy `.<pid>.mcp-steroid`
         * layout. Returns `null` if neither pattern matches.
         */
        fun pidFromFileName(fileName: String): Long? {
            val match = FILE_NAME_REGEX.matchEntire(fileName) ?: return null
            return match.groupValues[2].toLongOrNull()
        }

        private val FILE_NAME_REGEX = Regex("""^(\.?)(\d+)\.mcp-steroid$""")
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
