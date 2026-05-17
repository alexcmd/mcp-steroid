/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class NpxToolBridgeClientTest {
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var httpClient: HttpClient
    private var port: Int = 0
    private var receivedAuth: String? = null
    private var receivedBody: String? = null

    @BeforeEach
    fun setUp() {
        port = freePort()
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                post("/npx/v1/tools/call/stream") {
                    receivedAuth = call.request.headers["Authorization"]
                    receivedBody = call.receiveText()
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        val result = ToolCallResult(
                            content = listOf(ContentItem.Text("ok")),
                            isError = false,
                        )
                        write("event: result\n")
                        write(
                            "data: " + buildJsonObject {
                                put("type", "result")
                                put("result", McpJson.encodeToJsonElement(ToolCallResult.serializer(), result))
                            } + "\n\n"
                        )
                        flush()
                    }
                }
            }
        }.also { it.start(wait = false) }
        runBlocking { server.monitor.subscribe(ApplicationStarted) {} }

        httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 2_000
            }
            expectSuccess = false
        }
    }

    @AfterEach
    fun tearDown() {
        httpClient.close()
        server.stop(0L, 0L)
    }

    @Test
    fun `bridge client sends bearer token and rewritten original project name`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val route = ProjectRoute(
            idePid = 42,
            bridgeBaseUrl = "http://127.0.0.1:$port",
            token = "secret-token",
            originalProjectName = "original-project",
            exposedProjectName = "original-project-abcdefgh",
            projectPath = tempDir.toString(),
            realProjectHome = tempDir.toRealPath(),
            hash8 = "abcdefgh",
            ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        )
        val bridge = NpxToolBridgeClient(
            routing = NpxProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route, "steroid_execute_code") {
            put("project_name", route.originalProjectName)
            put("task_id", "task")
            put("reason", "test")
            put("code", "println(1)")
        }

        assertEquals(false, result.isError)
        assertEquals("Bearer secret-token", receivedAuth)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_execute_code", json["name"]?.jsonPrimitive?.content)
        assertEquals(
            "original-project",
            json["arguments"]?.jsonObject?.get("project_name")?.jsonPrimitive?.content,
        )
    }
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }
