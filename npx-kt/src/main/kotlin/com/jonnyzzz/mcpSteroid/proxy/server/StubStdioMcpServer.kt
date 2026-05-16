/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.McpStdioServer
import com.jonnyzzz.mcpSteroid.mcp.PromptsCapability
import com.jonnyzzz.mcpSteroid.mcp.ResourcesCapability
import com.jonnyzzz.mcpSteroid.mcp.ServerCapabilities
import com.jonnyzzz.mcpSteroid.mcp.ServerInfo
import com.jonnyzzz.mcpSteroid.mcp.ToolsCapability
import com.jonnyzzz.mcpSteroid.proxy.NpxKtServices
import com.jonnyzzz.mcpSteroid.proxy.ProxyVersionMetadata

/**
 * Boot a real MCP stdio server inside the npx-kt CLI process.
 *
 * Wiring:
 *  - [McpServerCore] declares server identity + advertised capabilities.
 *  - [StubMcpSteroidTools.registerAll] registers every steroid_* tool spec onto
 *    the core. The handlers themselves are not yet implemented in npx-kt — see
 *    [StubMcpSteroidTools] — so calling a tool returns an error, but
 *    `tools/list` / `prompts/list` / `resources/list` describe the full surface.
 *  - [McpStdioServer] runs the transport on [NpxKtServices.mcpStdin] /
 *    [NpxKtServices.mcpStdout] (NDJSON or framed, auto-detected from the first
 *    inbound frame).
 *
 * [NpxKtServices] owns the original `System.in` / `System.out` references —
 * not the JVM globals after main has already routed `System.out` to stderr.
 * Suspends until [NpxKtServices.mcpStdin] reaches EOF.
 */
suspend fun runStubStdioMcpServer(services: NpxKtServices) {
    val server = McpServerCore(
        serverInfo = ServerInfo(
            name = "mcp-steroid-proxy",
            version = ProxyVersionMetadata.getProxyVersion(),
        ),
        capabilities = ServerCapabilities(
            tools = ToolsCapability(listChanged = true),
            prompts = PromptsCapability(listChanged = true),
            resources = ResourcesCapability(subscribe = false, listChanged = true),
        ),
    )

    StubMcpSteroidTools(services).registerAll(server)

    McpStdioServer(server, input = services.mcpStdin, output = services.mcpStdout).run()
}
