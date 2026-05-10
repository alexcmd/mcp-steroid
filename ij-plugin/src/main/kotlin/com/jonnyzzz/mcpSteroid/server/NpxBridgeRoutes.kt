/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ResourceReadResult
import com.jonnyzzz.mcpSteroid.server.ListProductsResponse
import com.jonnyzzz.mcpSteroid.server.ServerMetadataResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun Route.installNpxBridgeRoutes(
    serverCoreProvider: () -> McpServerCore,
    mcpUrlProvider: () -> String
) {
    route("/npx/v1") {
        get("/metadata") {
            val bridge = NpxBridgeService.getInstance()
            val payload = bridge.buildMetadata(mcpUrlProvider())
            call.respondJson(payload, NpxBridgeMetadataResponse.serializer())
        }

        get("/server-metadata") {
            val bridge = NpxBridgeService.getInstance()
            val payload = bridge.buildServerMetadata(mcpUrlProvider())
            call.respondJson(payload, ServerMetadataResponse.serializer())
        }

        get("/products") {
            val bridge = NpxBridgeService.getInstance()
            val payload = bridge.buildProducts()
            call.respondJson(payload, ListProductsResponse.serializer())
        }

        get("/projects") {
            val bridge = NpxBridgeService.getInstance()
            val payload = bridge.buildProjects(mcpUrlProvider())
            call.respondJson(payload, NpxBridgeProjectsResponse.serializer())
        }

        post("/projects/stream") {
            val service = ProjectsStreamService.getInstance()
            call.streamProjectsNdjson(
                projectsFlow = service.projects,
                instanceId = service.ideInstanceId,
                pid = service.idePid,
                nextSeq = { service.nextSeq() },
                onClientInfo = { service.clientConnected(it) },
            )
        }

        get("/windows") {
            val bridge = NpxBridgeService.getInstance()
            val payload = bridge.buildWindows(mcpUrlProvider())
            call.respondJson(payload, NpxBridgeWindowsResponse.serializer())
        }

        get("/summary") {
            val bridge = NpxBridgeService.getInstance()
            val payload = bridge.buildSummary(mcpUrlProvider())
            call.respondJson(payload, NpxBridgeSummaryResponse.serializer())
        }

        get("/resources") {
            val bridge = NpxBridgeService.getInstance()
            val payload = bridge.buildResources(serverCoreProvider())
            call.respondJson(payload, NpxBridgeResourcesResponse.serializer())
        }

        get("/resources/read") {
            val uri = call.request.queryParameters["uri"]
            if (uri.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing uri query parameter")
                return@get
            }
            val bridge = NpxBridgeService.getInstance()
            val payload = bridge.readResource(serverCoreProvider(), uri)
            if (payload == null) {
                call.respond(HttpStatusCode.NotFound, "Resource not found: $uri")
                return@get
            }
            call.respondJson(payload, ResourceReadResult.serializer())
        }

        post("/tools/call") {
            val request = call.parseToolCallRequestOrRespondBadRequest() ?: return@post
            val bridge = NpxBridgeService.getInstance()

            var result: JsonElement? = null
            var errorMessage: String? = null
            bridge.streamToolCall(serverCoreProvider(), request) { event ->
                when (event.eventType()) {
                    "result" -> result = event["result"]
                    "error" -> errorMessage = event["message"]?.jsonPrimitive?.contentOrNull
                }
            }

            if (result != null) {
                call.respondText(
                    text = result.toString(),
                    contentType = ContentType.Application.Json
                )
            } else {
                call.respond(
                    HttpStatusCode.BadGateway,
                    errorMessage ?: "Tool call failed"
                )
            }
        }

        post("/tools/call/stream") {
            val request = call.parseToolCallRequestOrRespondBadRequest() ?: return@post
            val bridge = NpxBridgeService.getInstance()
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                bridge.streamToolCall(serverCoreProvider(), request) { event ->
                    val eventType = event.eventType() ?: "message"
                    write("event: $eventType\n")
                    write("data: ${event}\n\n")
                    flush()
                }
            }
        }
    }
}

private suspend inline fun <reified T> io.ktor.server.application.ApplicationCall.respondJson(
    payload: T,
    serializer: kotlinx.serialization.KSerializer<T>
) {
    respondText(
        text = McpJson.encodeToString(serializer, payload),
        contentType = ContentType.Application.Json
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.parseToolCallRequestOrRespondBadRequest(): NpxBridgeToolCallRequest? {
    val body = receiveText()
    return try {
        McpJson.decodeFromString(NpxBridgeToolCallRequest.serializer(), body)
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, "Invalid request body: ${e.message}")
        null
    }
}

private fun JsonObject.eventType(): String? = this["type"]?.jsonPrimitive?.contentOrNull
