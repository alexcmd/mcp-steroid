/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeDiscoveryService
import com.jonnyzzz.mcpSteroid.proxy.monitor.IntelliJPortDiscovery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.jonnyzzz.mcpSteroid.server.NPX_PROJECTS_STREAM_PATH
import com.jonnyzzz.mcpSteroid.server.NpxStreamClientInfo
import com.jonnyzzz.mcpSteroid.server.NpxStreamJson
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.PrintStream
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One row in the `backend` subcommand's output. Two sources:
 *  - [FromMarker]: discovered via `~/.<pid>.mcp-steroid` JSON marker. Projects
 *    are queryable through the IDE's `/npx/v1/projects/stream`.
 *  - [FromPort]: discovered via port scan of the IntelliJ built-in HTTP
 *    server (`/api/about`). No project list is available — older IDEs and
 *    IDEs without the mcp-steroid plugin still surface here so the operator
 *    sees the full picture.
 */
internal sealed interface BackendRow {
    /**
     * Full IDE identifier for the text header — includes the marketing version
     * already. Examples:
     *  - Marker: `"IntelliJ IDEA 2025.3.3"` (name + version concatenated)
     *  - Port:   `"IntelliJ IDEA 2026.1.1"` (productFullName already has the
     *            version baked in by `/api/about`)
     */
    val displayName: String

    /**
     * Short locator shown after [displayName] in parens. Carries the most
     * useful "how do I reach this IDE" hint per source — `pid` for markers,
     * `build` + `port` for port-discovered.
     */
    val locatorLabel: String

    data class FromMarker(
        val ide: DiscoveredIde,
        val projects: List<ProjectInfo>?,
        val errorMessage: String? = null,
    ) : BackendRow {
        override val displayName: String
            get() = "${ide.marker.ide.name} ${ide.marker.ide.version}".trim()
        override val locatorLabel: String get() = "pid ${ide.pid}"
    }

    data class FromPort(
        val ide: DiscoveredIdeByPort,
    ) : BackendRow {
        override val displayName: String
            get() = ide.productFullName ?: ide.productName ?: "(unknown JetBrains IDE)"
        override val locatorLabel: String get() = buildString {
            ide.buildNumber?.let { append("build ").append(it).append(", ") }
            append("port ").append(ide.port)
        }
    }
}

/**
 * Entry point invoked by [runCli] when [CliMode.Backend] is selected. Walks
 * both discovery paths in parallel:
 *  1. `~/.<pid>.mcp-steroid` JSON markers → IDEs with project lists.
 *  2. Port scan of `127.0.0.1:63342..63361` and `:64342..64361` → IDEs
 *     reachable through their built-in HTTP server, including older ones
 *     that never wrote a marker.
 *
 * Renders one block per IDE on [out]. Exit status is always 0: "no IDE was
 * running" is a steady state on most machines, not a CLI error.
 *
 * @param json `true` ⇒ emit a single machine-readable JSON object instead of
 *  the human-readable banner+list. The JSON is pretty-printed so a human can
 *  still read it without `jq`; `jq` accepts both forms equally.
 */
internal fun runBackendCommand(out: PrintStream, json: Boolean = false) {
    val homeDir = File(System.getProperty("user.home"))
    val allowHosts = listOf("localhost", "127.0.0.1", "host.docker.internal")
    val discovery = IdeDiscoveryService(homeDir = homeDir, allowHosts = allowHosts)

    // Short-lived HTTP client. The MCP path uses an infinite-stream client;
    // here we want fast failure so a stuck IDE doesn't hang the CLI.
    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
    }
    val portDiscovery = IntelliJPortDiscovery(httpClient = httpClient)

    try {
        discovery.scanOnce()
        val ides = discovery.ides.value
            .sortedWith(compareBy({ it.marker.ide.name }, { it.pid }))

        val rows = runBlocking(Dispatchers.IO) {
            // Run marker-fetch and port-scan concurrently — both hit localhost
            // and they're independent, so there's no reason to serialise.
            val markerRowsAsync = async {
                collectMarkerSnapshots(httpClient, ides, perIdeTimeout = 8.seconds)
            }
            val portIdesAsync = async {
                collectPortDiscoveredIdes(portDiscovery)
            }
            val markerRows = markerRowsAsync.await()
            val portIdes = portIdesAsync.await()
            mergeRows(markerRows, portIdes)
        }
        if (json) {
            renderBackendJson(rows, out)
        } else {
            renderBackendOutput(rows, out)
        }
    } finally {
        portDiscovery.close()
        httpClient.close()
    }
}

/**
 * One-shot port scan. Wraps [IntelliJPortDiscovery.scanOnce] + reads the
 * resulting set. Separate function so tests can drive it independently.
 */
internal suspend fun collectPortDiscoveredIdes(
    portDiscovery: IntelliJPortDiscovery,
): Set<DiscoveredIdeByPort> {
    portDiscovery.scanOnce()
    return portDiscovery.detected.value
}

/**
 * Merge marker rows (rich, with projects) and port-discovered IDEs (basic,
 * no projects). A port IDE is dropped when a marker row already represents
 * the same IDE — heuristic: same build number. This catches the common case
 * where one running IDE shows up in BOTH discoveries (it has mcp-steroid
 * plugin AND its built-in HTTP server is on a scanned port). When the IDE
 * has no mcp-steroid plugin, marker is absent and the port row survives.
 *
 * Exposed `internal` so a dedupe test can drive it without HTTP.
 */
internal fun mergeRows(
    markerRows: List<BackendRow.FromMarker>,
    portIdes: Set<DiscoveredIdeByPort>,
): List<BackendRow> {
    // PidMarker writes `IdeInfo.build = "IU-261.23567.138"` (with the product
    // code prefix), while `/api/about` returns `"261.23567.138"` (without).
    // Normalise both ends to the same shape before comparing — otherwise the
    // same running IDE is never deduplicated.
    val markerBuilds: Set<String> = markerRows
        .mapNotNull { normaliseBuildForDedup(it.ide.marker.ide.build) }
        .toSet()
    val deduplicatedPortRows = portIdes
        .asSequence()
        .filter { ide ->
            val key = normaliseBuildForDedup(ide.buildNumber)
            key == null || key !in markerBuilds
        }
        .sortedBy { it.port }
        .map { BackendRow.FromPort(it) }
        .toList()
    return markerRows + deduplicatedPortRows
}

/**
 * Strip a leading product-code prefix (letters + hyphen, e.g. `IU-`, `PC-`,
 * `GO-`) so marker builds (`IU-261.23567.138`) compare equal to `/api/about`
 * builds (`261.23567.138`). Returns `null` for null/blank input so callers
 * can use it as a Map key without further filtering.
 */
private fun normaliseBuildForDedup(build: String?): String? {
    if (build.isNullOrBlank()) return null
    return build.replaceFirst(Regex("^[A-Z]+-"), "")
}

/**
 * Connect to each marker-discovered IDE in parallel, await the first
 * `type=snapshot` envelope, then close. Returns one row per input IDE in
 * the same order.
 *
 * Per-IDE timeout is enforced inside the helper so one slow IDE doesn't
 * block the others. A timeout / error is recorded on the row's
 * [BackendRow.FromMarker.projects] = `null` so the renderer can flag the
 * IDE without taking down the whole list.
 */
internal suspend fun collectMarkerSnapshots(
    httpClient: HttpClient,
    ides: List<DiscoveredIde>,
    perIdeTimeout: Duration,
): List<BackendRow.FromMarker> = coroutineScope {
    ides.map { ide ->
        async { fetchSnapshotForIde(httpClient, ide, perIdeTimeout) }
    }.awaitAll()
}

private suspend fun fetchSnapshotForIde(
    httpClient: HttpClient,
    ide: DiscoveredIde,
    timeout: Duration,
): BackendRow.FromMarker {
    val snapshot = try {
        withTimeoutOrNull(timeout) { fetchFirstSnapshot(httpClient, ide) }
    } catch (e: Exception) {
        return BackendRow.FromMarker(ide, projects = null, errorMessage = e.message ?: e::class.simpleName)
    }
    return when (snapshot) {
        null -> BackendRow.FromMarker(ide, projects = null, errorMessage = "timed out after $timeout")
        else -> BackendRow.FromMarker(ide, projects = snapshot)
    }
}

/**
 * Open `/npx/v1/projects/stream`, drain envelopes until the first `snapshot`,
 * return its `projects` list (empty list if the IDE has nothing open). The
 * caller's `withTimeoutOrNull` bounds total time including the connect.
 */
private suspend fun fetchFirstSnapshot(
    httpClient: HttpClient,
    ide: DiscoveredIde,
): List<ProjectInfo> {
    val base = ide.mcpUrl.trimEnd('/').removeSuffix("/mcp")
    val url = base + NPX_PROJECTS_STREAM_PATH
    val token = ide.marker.token
    val body = NpxStreamJson.encodeClientInfo(
        NpxStreamClientInfo(
            client = "mcp-steroid-proxy (backend)",
            clientPid = ProcessHandle.current().pid(),
            clientVersion = loadProxyVersion(),
            clientInstanceId = "backend-${UUID.randomUUID()}",
            platform = System.getProperty("os.name"),
            arch = System.getProperty("os.arch"),
        )
    )

    return httpClient.preparePost(url) {
        headers {
            append(HttpHeaders.ContentType, "application/json")
            if (token.isNotEmpty()) {
                append(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        setBody(body)
    }.execute { response ->
        if (response.status.value !in 200..299) {
            error("HTTP ${response.status.value}")
        }
        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (line.isBlank()) continue
            val env = try {
                NpxStreamJson.decodeEnvelope(line)
            } catch (e: Exception) {
                // Skip malformed envelopes; the snapshot is on a later line.
                continue
            }
            if (env.type == "snapshot") {
                return@execute env.projects ?: emptyList()
            }
        }
        error("stream closed before a snapshot envelope arrived")
    }
}

/**
 * Pure renderer — separated from [runBackendCommand] so a unit test can
 * exercise every formatting branch without touching the network.
 *
 * Output shape:
 * ```
 * devrig v<version> — <tagline>
 *
 * Discovered N IDE(s):
 *
 *   [1] <IDE name> <version> (<locator>)
 *         <project-name>  →  <project-path>
 *         …
 *
 *   [2] <IDE name> <version> (<locator>)
 *         (mcp-steroid plugin not installed — project list unavailable)
 *
 * ```
 * The opening banner identifies the tool, a blank line separates banner from
 * list, the list itself uses `[N]` index markers so it visibly is a list,
 * and the trailing blank line gives shells a clean separator.
 */
internal fun renderBackendOutput(rows: List<BackendRow>, out: PrintStream) {
    out.println("$BRAND_NAME v${loadProxyVersion()} — $BRAND_TAGLINE")
    out.println()
    if (rows.isEmpty()) {
        out.println("No IDEs detected.")
        out.println()
        return
    }
    val noun = if (rows.size == 1) "IDE" else "IDEs"
    out.println("Discovered ${rows.size} $noun:")
    out.println()
    for ((index, row) in rows.withIndex()) {
        out.println("  [${index + 1}] ${row.displayName} (${row.locatorLabel})")
        when (row) {
            is BackendRow.FromMarker -> renderMarkerProjects(row, out)
            is BackendRow.FromPort -> out.println(
                "        (mcp-steroid plugin not installed — project list unavailable)"
            )
        }
        if (index < rows.lastIndex) out.println()
    }
    // Trailing blank line so piped consumers / terminals don't glue the
    // next prompt to the last project line.
    out.println()
}

/**
 * Pretty-printed JSON renderer for the `backend --json` form. Designed for
 * scripted consumption (`mcp-steroid-proxy backend --json | jq …`):
 *  - **No banner** — stdout is one JSON document, nothing else.
 *  - **Top-level object** with `tool` (meta) + `ides` (data array). Same shape
 *    pattern `kubectl get … -o json` uses, so jq-savvy users feel at home.
 *  - **`source` discriminator** (`"marker"` | `"port"`) on every IDE — lets
 *    jq filter by discovery path without checking which fields are present.
 *  - **Source-specific fields are present only on the matching source** so
 *    `.projects` is reliably a list (or null) on marker rows, never confused
 *    with port-only rows that have no project list to begin with.
 *  - **Pretty-printed**: humans can read without `jq -P`, scripts don't care.
 *
 * Example query:
 *   mcp-steroid-proxy backend --json | jq '.ides[] | select(.source=="marker") | .projects[]?'
 */
internal fun renderBackendJson(rows: List<BackendRow>, out: PrintStream) {
    val json = Json { prettyPrint = true; encodeDefaults = true }
    val payload = buildJsonObject {
        put("tool", buildJsonObject {
            put("name", BRAND_NAME)
            put("version", loadProxyVersion())
        })
        put("ides", buildJsonArray {
            for (row in rows) {
                add(rowToJson(row))
            }
        })
    }
    out.println(json.encodeToString(JsonObject.serializer(), payload))
}

private fun rowToJson(row: BackendRow): JsonObject = when (row) {
    is BackendRow.FromMarker -> buildJsonObject {
        put("source", "marker")
        put("name", row.ide.marker.ide.name)
        put("version", row.ide.marker.ide.version)
        put("build", row.ide.marker.ide.build)
        put("pid", row.ide.pid)
        put("mcpUrl", row.ide.mcpUrl)
        // Projects shape mirrors the wire `ProjectInfo` — `name` + `path` per entry.
        // `null` (not absent) when fetch failed; `unreachable` carries the reason.
        if (row.projects != null) {
            put("projects", buildJsonArray {
                for (p in row.projects) add(buildJsonObject {
                    put("name", p.name)
                    put("path", p.path)
                })
            })
        } else {
            // Explicit null so `.projects` is always present on marker rows;
            // jq users can `select(.projects == null)` to filter unreachable ones.
            putJsonNull("projects")
            put("unreachable", row.errorMessage ?: "unreachable")
        }
    }
    is BackendRow.FromPort -> buildJsonObject {
        put("source", "port")
        // `/api/about` reports the marketing version mashed into the product
        // name (e.g. "IntelliJ IDEA 2026.1.1") and the bare build number as
        // a separate field. Surface the full name as `displayName` so scripts
        // have one composite string to render, and keep the raw fields below
        // so structured consumers can still slice on `buildNumber`, etc.
        put("displayName", row.displayName)
        put("port", row.ide.port)
        put("baseUrl", row.ide.baseUrl)
        row.ide.productName?.let { put("productName", it) }
        row.ide.productFullName?.let { put("productFullName", it) }
        row.ide.edition?.let { put("edition", it) }
        row.ide.baselineVersion?.let { put("baselineVersion", it) }
        row.ide.buildNumber?.let { put("buildNumber", it) }
        // Hard signal: no plugin ⇒ no project list. The CLI text renderer says
        // this in prose; the JSON form is explicit so scripts don't have to
        // guess from "no projects field".
        put("pluginInstalled", false)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonNull(key: String) {
    put(key, kotlinx.serialization.json.JsonNull)
}

private fun renderMarkerProjects(row: BackendRow.FromMarker, out: PrintStream) {
    when {
        row.projects == null -> {
            val reason = row.errorMessage ?: "unreachable"
            out.println("        (unreachable: $reason)")
        }
        row.projects.isEmpty() -> {
            out.println("        (no open projects)")
        }
        else -> {
            // Right-pad project names so `→` arrows line up — turns the inner
            // list into a small two-column table when more than one project
            // is open. Cap the pad so a single unusually long name doesn't
            // push every other row's path off the screen.
            val padWidth = row.projects.maxOf { it.name.length }.coerceAtMost(40)
            for (p in row.projects) {
                val paddedName = p.name.padEnd(padWidth)
                out.println("        $paddedName  →  ${p.path}")
            }
        }
    }
}
