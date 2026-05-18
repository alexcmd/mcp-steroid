/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeMonitorStatus
import com.jonnyzzz.mcpSteroid.server.ApplyPatchHunk
import com.jonnyzzz.mcpSteroid.server.ApplyPatchRequest
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
    private val beforeResultEvents = mutableListOf<String>()
    private var sseResponse: String? = null
    private var httpStatus = HttpStatusCode.OK
    private var httpBody = ""

    @BeforeEach
    fun setUp() {
        port = freePort()
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                post("/npx/v1/tools/call/stream") {
                    receivedAuth = call.request.headers["Authorization"]
                    receivedBody = call.receiveText()
                    if (httpStatus != HttpStatusCode.OK) {
                        call.respondText(httpBody, ContentType.Text.Plain, httpStatus)
                        return@post
                    }
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        val custom = sseResponse
                        if (custom != null) {
                            write(custom)
                            flush()
                            return@respondTextWriter
                        }
                        beforeResultEvents.forEach { write(it) }
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
        beforeResultEvents.clear()
        sseResponse = null
        httpStatus = HttpStatusCode.OK
        httpBody = ""
        httpClient.close()
        server.stop(0L, 0L)
    }

    @Test
    fun `bridge client sends bearer token and rewritten original project name`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val route = route(tempDir)
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

    @Test
    fun `bridge client omits authorization header when token is empty`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val bridge = NpxToolBridgeClient(
            routing = NpxProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir, token = ""), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(false, result.isError)
        assertEquals(null, receivedAuth)
    }

    @Test
    fun `apply patch bridge handler forwards required task id`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().values.single()
        val handler = NpxApplyPatchToolHandler(NpxToolBridgeClient(routing, httpClient))
        val filePath = projectHome.resolve("A.kt").toString()

        val result = handler.applyPatch(
            projectName = route.exposedProjectName,
            applyPatchRequest = ApplyPatchRequest(
                taskId = "patch-task",
                dryRun = true,
                hunks = listOf(
                    ApplyPatchHunk(
                        filePath = filePath,
                        oldString = "old",
                        newString = "new",
                    )
                ),
            ),
        )

        assertEquals(false, result.isError)
        assertEquals("Bearer secret-token", receivedAuth)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_apply_patch", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("original-project", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("patch-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("true", arguments["dry_run"]?.jsonPrimitive?.content)
        val hunk = arguments["hunks"]?.jsonArray?.single()?.jsonObject ?: error("missing hunk: $json")
        assertEquals(filePath, hunk["file_path"]?.jsonPrimitive?.content)
        assertEquals("old", hunk["old_string"]?.jsonPrimitive?.content)
        assertEquals("new", hunk["new_string"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute code bridge handler forwards timeout dialog killer and progress events`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        beforeResultEvents += """
            event: progress
            data: {"type":"progress","message":"compile started"}

        """.trimIndent() + "\n"
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().values.single()
        val progressMessages = mutableListOf<String>()
        val handler = NpxExecuteCodeToolHandler(NpxToolBridgeClient(routing, httpClient))

        val result = handler.executeCode(
            projectName = route.exposedProjectName,
            execCodeParams = ExecCodeParams(
                taskId = "exec-task",
                code = "println(1)",
                reason = "verify execute-code argument and progress forwarding",
                timeout = 17,
                dialogKiller = true,
            ),
            callProgress = object : McpProgressReporter {
                override fun report(message: String) {
                    progressMessages += message
                }
            },
        )

        assertEquals(false, result.isError)
        assertEquals(listOf("compile started"), progressMessages)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_execute_code", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("original-project", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("exec-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("17", arguments["timeout"]?.jsonPrimitive?.content)
        assertEquals("true", arguments["dialog_killer"]?.jsonPrimitive?.content)
    }

    @Test
    fun `bridge client returns error result for malformed SSE data`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        sseResponse = """
            event: result
            data: {not json}

        """.trimIndent() + "\n"
        val bridge = NpxToolBridgeClient(
            routing = NpxProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("Malformed SSE data"))
    }

    @Test
    fun `bridge client returns error result for SSE error event`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        sseResponse = """
            event: error
            data: {"type":"error","message":"upstream failed"}

        """.trimIndent() + "\n"
        val bridge = NpxToolBridgeClient(
            routing = NpxProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("upstream failed"))
    }

    @Test
    fun `bridge client returns error result for upstream 401`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        httpStatus = HttpStatusCode.Unauthorized
        httpBody = "bad token"
        val bridge = NpxToolBridgeClient(
            routing = NpxProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("HTTP 401"))
        assertTrue(result.errorText().contains("/npx/v1/tools/call/stream"))
        assertTrue(result.errorText().contains("bad token"))
    }

    @Test
    fun `bridge client returns error result for upstream 500`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        httpStatus = HttpStatusCode.InternalServerError
        httpBody = "bridge exploded"
        val bridge = NpxToolBridgeClient(
            routing = NpxProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("HTTP 500"))
        assertTrue(result.errorText().contains("bridge exploded"))
    }

    @Test
    fun `bridge client returns error result when result event has no result field`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        sseResponse = """
            event: result
            data: {"type":"result"}

        """.trimIndent() + "\n"
        val bridge = NpxToolBridgeClient(
            routing = NpxProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("result event did not include result"))
    }

    @Test
    fun `bridge client returns error result when SSE stream closes without result`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        sseResponse = """
            event: progress
            data: {"type":"progress","message":"still running"}

        """.trimIndent() + "\n"
        val bridge = NpxToolBridgeClient(
            routing = NpxProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("No result received"))
    }

    @Test
    fun `bridge client decodes multiline SSE data frame`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val resultBody = ToolCallResult(
            content = listOf(ContentItem.Text("multiline ok")),
            isError = false,
        )
        sseResponse = """
            event: result
            data: {"type":"result",
            data: "result": ${McpJson.encodeToJsonElement(ToolCallResult.serializer(), resultBody)}}

        """.trimIndent() + "\n"
        val bridge = NpxToolBridgeClient(
            routing = NpxProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(false, result.isError)
        assertEquals("multiline ok", (result.content.single() as ContentItem.Text).text)
    }

    private fun routingService(vararg states: IdeMonitorState): NpxProjectRoutingService =
        NpxProjectRoutingService { states.associateBy { it.ide.pid } }

    private fun discoveredIde(pid: Long, projectHome: Path): DiscoveredIde =
        DiscoveredIde(
            pid = pid,
            mcpUrl = "http://127.0.0.1:$port/mcp",
            markerPath = projectHome.resolve("$pid.mcp-steroid").toString(),
            marker = PidMarker(
                pid = pid,
                mcpUrl = "http://127.0.0.1:$port/mcp",
                port = port,
                token = "secret-token",
                ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
                plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
                createdAt = "2026-05-17T00:00:00Z",
            ),
        )

    private fun route(tempDir: Path, token: String = "secret-token"): ProjectRoute =
        ProjectRoute(
            idePid = 42,
            bridgeBaseUrl = "http://127.0.0.1:$port",
            token = token,
            originalProjectName = "original-project",
            exposedProjectName = "original-project-abcdefgh",
            projectPath = tempDir.toString(),
            realProjectHome = tempDir.toRealPath(),
            hash8 = "abcdefgh",
            ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        )
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun ToolCallResult.errorText(): String =
    (content.single() as ContentItem.Text).text
