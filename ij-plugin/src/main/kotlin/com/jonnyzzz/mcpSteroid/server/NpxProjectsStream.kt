/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Wire-protocol envelope for the `/npx/v1/projects/stream` NDJSON response.
 *
 * Backward/forward compatibility: decoders MUST use [NpxStreamJson] (which
 * sets `ignoreUnknownKeys = true`). New optional fields SHOULD be added with
 * a default so old writers stay compatible.
 *
 * Envelope shape uses one struct with optional fields rather than a sealed
 * polymorphic hierarchy — that keeps the wire format trivial to extend
 * without breaking strict decoders, and matches the `eventType()` style
 * already in use by [NpxBridgeService.streamToolCall].
 */
@Serializable
data class NpxStreamEnvelope(
    /** "snapshot" | "ping" — newer servers may add types; clients ignore unknown values. */
    val type: String,
    val seq: Long,
    val sentAt: String,
    /** Stable per-IDE-process identifier so clients can multiplex concurrent IDE connections. */
    val instanceId: String,
    /** OS pid of the IDE process. */
    val pid: Long,
    /** Present when `type == "snapshot"`. Absent (null) for pings and other events. */
    val projects: List<ProjectInfo>? = null,
)

/**
 * Client-side identification sent in the POST body of `/npx/v1/projects/stream`.
 * Logged on the IDE for debugging / monitoring.
 */
@Serializable
data class NpxStreamClientInfo(
    val client: String = "unknown",
    val clientPid: Long? = null,
    val clientVersion: String? = null,
    val clientInstanceId: String? = null,
    val platform: String? = null,
    val arch: String? = null,
)

/**
 * Tolerant codec for the wire protocol — `ignoreUnknownKeys = true` is the
 * forward-compat hook used on both ends of the stream.
 */
object NpxStreamJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    fun encodeEnvelope(envelope: NpxStreamEnvelope): String =
        json.encodeToString(NpxStreamEnvelope.serializer(), envelope)

    fun decodeEnvelope(line: String): NpxStreamEnvelope =
        json.decodeFromString(NpxStreamEnvelope.serializer(), line)

    fun decodeClientInfo(body: String): NpxStreamClientInfo =
        json.decodeFromString(NpxStreamClientInfo.serializer(), body)
}

/**
 * Streams the projects-stream NDJSON response into [ApplicationCall].
 *
 * Behaviour:
 *  - Reads the request body as [NpxStreamClientInfo] (best-effort: malformed
 *    or empty body falls back to defaults so clients on older protocols
 *    still connect).
 *  - Emits a `snapshot` envelope immediately for the current state.
 *  - Emits a fresh `snapshot` envelope on every distinct value of
 *    [projectsFlow] — `collectLatest` ensures we never queue stale snapshots
 *    when changes pile up faster than the consumer drains.
 *  - Emits a `ping` envelope every [pingInterval] so the client can detect
 *    a half-open TCP connection without waiting for the next change.
 *
 * Pure-function shape (no `Project`, no `service<>`) so the route is
 * testable in isolation against an in-memory `Flow<List<ProjectInfo>>`.
 */
internal suspend fun ApplicationCall.streamProjectsNdjson(
    projectsFlow: Flow<List<ProjectInfo>>,
    instanceId: String,
    pid: Long,
    nextSeq: () -> Long,
    pingInterval: Duration = 5.seconds,
    onClientInfo: (NpxStreamClientInfo) -> Unit,
) {
    val rawBody = try { receiveText() } catch (e: Exception) { "" }
    val clientInfo = if (rawBody.isBlank()) {
        NpxStreamClientInfo()
    } else {
        try {
            NpxStreamJson.decodeClientInfo(rawBody)
        } catch (e: Exception) {
            NpxStreamClientInfo(client = "unparseable: ${e.message}")
        }
    }
    onClientInfo(clientInfo)

    respondTextWriter(contentType = NDJSON_CONTENT_TYPE) {
        coroutineScope {
            val pings = launch {
                while (isActive) {
                    delay(pingInterval)
                    if (!coroutineContext.isActive) break
                    val ping = NpxStreamEnvelope(
                        type = "ping",
                        seq = nextSeq(),
                        sentAt = nowIso(),
                        instanceId = instanceId,
                        pid = pid,
                    )
                    write(NpxStreamJson.encodeEnvelope(ping))
                    write("\n")
                    flush()
                }
            }

            try {
                projectsFlow.collectLatest { projects ->
                    val snapshot = NpxStreamEnvelope(
                        type = "snapshot",
                        seq = nextSeq(),
                        sentAt = nowIso(),
                        instanceId = instanceId,
                        pid = pid,
                        projects = projects,
                    )
                    write(NpxStreamJson.encodeEnvelope(snapshot))
                    write("\n")
                    flush()
                }
            } finally {
                pings.cancel()
            }
        }
    }
}

internal val NDJSON_CONTENT_TYPE: ContentType = ContentType("application", "x-ndjson")

private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
