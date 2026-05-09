/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.thisLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Generic MCP stdio transport.
 *
 * Mirrors [McpHttpTransport][com.jonnyzzz.mcpSteroid.mcp] for HTTP: it reads framed
 * (or NDJSON) JSON-RPC messages from [input], dispatches each through an
 * [McpServerCore], and writes responses to [output] using the same framing the peer
 * is using. Server-initiated notifications and server-to-client requests (e.g.
 * `sampling/createMessage`) are streamed concurrently from the session's channels.
 *
 * The class is transport-only: it has no opinion about which tools, resources, or
 * prompts are exposed — those are configured on the [server] argument by the caller.
 *
 * Both streams are constructor-injected to keep the class testable. Production
 * callers typically pass `System.in` / `System.out`; tests pass
 * `ByteArrayInputStream` / `ByteArrayOutputStream`.
 *
 * Lifecycle:
 *   1. Caller invokes [run], which creates a fresh [McpSession] and launches two
 *      coroutines that drain the session's outgoing notification and request
 *      channels into framed bytes on [output].
 *   2. The reader loop pulls bytes from [input] on [Dispatchers.IO], feeds them to a
 *      [FramingBuffer], and dispatches each parsed frame to [server].
 *   3. The output framing mode is locked on the **first inbound frame** ("framed" or
 *      "ndjson"); subsequent writes match that mode.
 *   4. On EOF (or [IOException]) the reader exits, the session is closed (which
 *      drains its channels), and the coroutine returns.
 */
class McpStdioServer(
    private val server: McpServerCore,
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
) {
    private val log = thisLogger()

    @Volatile
    private var outputMode: String? = null

    private val outputLock = Any()

    /**
     * Run the stdio loop until [input] reaches EOF or the surrounding coroutine is
     * cancelled. Suspends; safe to call from `runBlocking { ... }` or `runTest { ... }`.
     */
    suspend fun run(): Unit = coroutineScope {
        val session = server.sessionManager.createSession()
        try {
            launchOutgoingPump(session)
            withContext(Dispatchers.IO) { readLoop(session) }
        } finally {
            // Close the session: this closes the notification + outgoing-request
            // channels so the pump coroutines drain remaining items and exit cleanly.
            server.sessionManager.removeSession(session.id)
        }
    }

    private fun CoroutineScope.launchOutgoingPump(session: McpSession) {
        launch {
            session.notifications().collect { notification ->
                val text = McpJson.encodeToString(JsonRpcNotification.serializer(), notification)
                writeFrame(text)
            }
        }
        launch {
            session.outgoingRequests().collect { request ->
                val text = McpJson.encodeToString(JsonRpcRequest.serializer(), request)
                writeFrame(text)
            }
        }
    }

    private suspend fun readLoop(session: McpSession) {
        val buffer = FramingBuffer()
        val buf = ByteArray(READ_BUFFER_SIZE)
        while (coroutineContext.isActive) {
            val n = readSafely(buf)
            if (n < 0) break          // EOF
            if (n == 0) continue       // shouldn't happen for blocking InputStream, but be defensive
            buffer.append(buf, n)
            drainBuffer(buffer, session)
        }
    }

    private fun readSafely(buf: ByteArray): Int {
        return try {
            input.read(buf)
        } catch (e: IOException) {
            log.info("[MCP stdio] read interrupted: ${e.message}")
            -1
        }
    }

    private suspend fun drainBuffer(buffer: FramingBuffer, session: McpSession) {
        while (true) {
            val frame = buffer.readNextFrame() ?: return
            if (outputMode == null) outputMode = frame.mode
            if (frame.payloadText.isBlank()) continue

            val response = try {
                server.handleMessage(frame.payloadText, session)
            } catch (e: Exception) {
                log.warn("[MCP stdio] handler failed", e)
                encodeInternalError(e.message ?: "Internal error")
            }
            if (response != null) writeFrame(response)
        }
    }

    private fun writeFrame(text: String) {
        val frame = when (outputMode) {
            "ndjson" -> encodeNdjsonMessage(text)
            else -> encodeFramedMessage(text)
        }
        synchronized(outputLock) {
            output.write(frame.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun encodeInternalError(message: String): String {
        val response = JsonRpcResponse(
            id = JsonNull,
            error = JsonRpcError(code = JsonRpcErrorCodes.INTERNAL_ERROR, message = message)
        )
        return McpJson.encodeToString(JsonRpcResponse.serializer(), response)
    }

    private companion object {
        private const val READ_BUFFER_SIZE = 8192
    }
}
