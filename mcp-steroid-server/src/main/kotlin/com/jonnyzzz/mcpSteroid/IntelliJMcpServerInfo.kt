package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable

/**
 * Reference to the IDE's bundled MCP server plugin (`com.intellij.mcpServer`)
 * if it is loaded **and** the user has enabled it.
 *
 * The plugin runs an independent ktor-server-cio listener (default port
 * 64342, bound to 127.0.0.1) and exposes two transports:
 *  - `streamUrl` — MCP streamable HTTP
 *  - `sseUrl`    — MCP SSE
 *
 * See `docs/intellij-builtin-servers.md` for the full lifecycle / auth
 * notes. Surfaced on [PidMarker.intellijMcpServer] so devrig can discover
 * and route to the bundled IntelliJ MCP tools alongside `mcp-steroid`'s own
 * server.
 *
 * All fields are optional with safe defaults so that markers written by
 * older IDEs without the bundled server still decode cleanly.
 */
@Serializable
data class IntelliJMcpServerInfo(
    /** `true` only if the plugin is loaded **and** `McpServerService.isRunning`. */
    val enabled: Boolean = false,
    /** TCP port where the IDE's MCP server accepts connections. `0` when [enabled] is false. */
    val port: Int = 0,
    /** Full URL to the MCP streamable HTTP endpoint. */
    val streamUrl: String = "",
    /** Full URL to the MCP SSE endpoint. */
    val sseUrl: String = "",
)
