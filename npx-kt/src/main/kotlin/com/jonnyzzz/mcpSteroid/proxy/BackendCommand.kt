/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeDiscoveryService
import com.jonnyzzz.mcpSteroid.proxy.monitor.IntelliJPortDiscovery
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
    val name: String
    val version: String
    /** Short locator shown after the IDE header, e.g. "pid 1234" or "port 63342". */
    val locatorLabel: String

    data class FromMarker(
        val ide: DiscoveredIde,
        val projects: List<ProjectInfo>?,
        val errorMessage: String? = null,
    ) : BackendRow {
        override val name: String get() = ide.marker.ide.name
        override val version: String get() = ide.marker.ide.version
        override val locatorLabel: String get() = "pid ${ide.pid}"
    }

    data class FromPort(
        val ide: DiscoveredIdeByPort,
    ) : BackendRow {
        override val name: String
            get() = ide.productFullName ?: ide.productName ?: "(unknown JetBrains IDE)"
        override val version: String
            get() = ide.buildNumber ?: ide.baselineVersion?.toString() ?: "(unknown)"
        override val locatorLabel: String get() = "port ${ide.port}"
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
 */
internal fun runBackendCommand(out: PrintStream) {
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
        renderBackendOutput(rows, out)
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
    val markerBuilds: Set<String> = markerRows.mapNotNull { it.ide.marker.ide.build }.toSet()
    val deduplicatedPortRows = portIdes
        .asSequence()
        .filter { it.buildNumber == null || it.buildNumber !in markerBuilds }
        .sortedBy { it.port }
        .map { BackendRow.FromPort(it) }
        .toList()
    return markerRows + deduplicatedPortRows
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
 * Format:
 *   <IDE name>  version <version>  (<locator>)
 *     <project-name> -> <project-path>
 *     ...
 *   (no open projects)                              ← FromMarker, empty list
 *   (unreachable: <reason>)                         ← FromMarker, null projects
 *   (mcp-steroid plugin not installed — project list unavailable)
 *                                                   ← FromPort variant
 */
internal fun renderBackendOutput(rows: List<BackendRow>, out: PrintStream) {
    if (rows.isEmpty()) {
        out.println("No IDEs detected.")
        return
    }
    for ((index, row) in rows.withIndex()) {
        if (index > 0) out.println()
        out.println("${row.name}  version ${row.version}  (${row.locatorLabel})")
        when (row) {
            is BackendRow.FromMarker -> renderMarkerProjects(row, out)
            is BackendRow.FromPort -> out.println(
                "  (mcp-steroid plugin not installed — project list unavailable)"
            )
        }
    }
}

private fun renderMarkerProjects(row: BackendRow.FromMarker, out: PrintStream) {
    when {
        row.projects == null -> {
            val reason = row.errorMessage ?: "unreachable"
            out.println("  (unreachable: $reason)")
        }
        row.projects.isEmpty() -> {
            out.println("  (no open projects)")
        }
        else -> {
            for (p in row.projects) {
                out.println("  ${p.name} -> ${p.path}")
            }
        }
    }
}
