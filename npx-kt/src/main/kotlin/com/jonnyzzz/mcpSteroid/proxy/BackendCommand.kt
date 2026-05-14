/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeDiscoveryService
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
 * One row in the `backend` subcommand's output. `null` [projects] means we
 * couldn't reach the IDE within the timeout (kept separate from "empty list"
 * so the renderer prints a different message).
 */
internal data class BackendIdeRow(
    val ide: DiscoveredIde,
    val projects: List<ProjectInfo>?,
    val errorMessage: String? = null,
)

/**
 * Entry point invoked by [runCli] when [CliMode.Backend] is selected.
 * One-shot: scans markers, opens one `/npx/v1/projects/stream` per IDE,
 * reads up to the first snapshot envelope, closes, renders.
 *
 * Returns nothing — failure cases (no IDEs, an IDE that doesn't respond)
 * are user-visible text on [out] but always exit 0; treating "no IDE was
 * running" as a CLI error would be hostile (it's the steady state on a
 * machine that just hasn't opened IntelliJ yet).
 */
internal fun runBackendCommand(out: PrintStream) {
    val homeDir = File(System.getProperty("user.home"))
    val allowHosts = listOf("localhost", "127.0.0.1", "host.docker.internal")
    val discovery = IdeDiscoveryService(homeDir = homeDir, allowHosts = allowHosts)

    discovery.scanOnce()
    val ides = discovery.ides.value
        .sortedWith(compareBy({ it.marker.ide.name }, { it.pid }))

    if (ides.isEmpty()) {
        out.println("No IDEs detected.")
        return
    }

    // Brief, bounded-lifetime HTTP client. The MCP path uses an infinite-stream
    // client; here we want fast failure so a stuck IDE doesn't hang the CLI.
    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    try {
        val rows = runBlocking(Dispatchers.IO) {
            collectBackendSnapshots(httpClient, ides, perIdeTimeout = 8.seconds)
        }
        renderBackendOutput(rows, out)
    } finally {
        httpClient.close()
    }
}

/**
 * Connect to each IDE in parallel, await the first `type=snapshot` envelope,
 * then close. Returns one [BackendIdeRow] per input IDE in the same order.
 *
 * Per-IDE timeout is enforced inside the helper so one slow IDE doesn't block
 * the others. A timeout / error is recorded on the row's [BackendIdeRow.projects]
 * = `null` so the renderer can flag the IDE without taking down the whole list.
 */
internal suspend fun collectBackendSnapshots(
    httpClient: HttpClient,
    ides: List<DiscoveredIde>,
    perIdeTimeout: Duration,
): List<BackendIdeRow> = coroutineScope {
    ides.map { ide ->
        async { fetchSnapshotForIde(httpClient, ide, perIdeTimeout) }
    }.awaitAll()
}

private suspend fun fetchSnapshotForIde(
    httpClient: HttpClient,
    ide: DiscoveredIde,
    timeout: Duration,
): BackendIdeRow {
    val snapshot = try {
        withTimeoutOrNull(timeout) { fetchFirstSnapshot(httpClient, ide) }
    } catch (e: Exception) {
        return BackendIdeRow(ide, projects = null, errorMessage = e.message ?: e::class.simpleName)
    }
    return when (snapshot) {
        null -> BackendIdeRow(ide, projects = null, errorMessage = "timed out after $timeout")
        else -> BackendIdeRow(ide, projects = snapshot)
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
 *   <IDE name>  version <version>  [pid <pid>]
 *     <project-name> -> <project-path>
 *     ...
 *   (no open projects)        ← when the IDE replied with an empty list
 *   (unreachable: <reason>)   ← when we couldn't reach the IDE in time
 */
internal fun renderBackendOutput(rows: List<BackendIdeRow>, out: PrintStream) {
    if (rows.isEmpty()) {
        out.println("No IDEs detected.")
        return
    }
    for ((index, row) in rows.withIndex()) {
        if (index > 0) out.println()
        val ide = row.ide.marker.ide
        out.println("${ide.name}  version ${ide.version}  (pid ${row.ide.pid})")
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
}
