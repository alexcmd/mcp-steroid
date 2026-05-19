package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable

/**
 * Reference to IntelliJ Platform's built-in web server
 * (`BuiltInServerManager`). This is independent of MCP Steroid's own
 * Ktor server and of the optional bundled MCP Server plugin.
 */
@Serializable
data class IntelliJWebServerInfo(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val baseUrl: String = "",
    val aboutUrl: String = "",
    val token: String = "",
    val tokenQueryParameter: String = "_ijt",
    val tokenHeader: String = "x-ijt",
)
