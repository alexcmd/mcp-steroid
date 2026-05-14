/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

internal data class BackendPort(
    val type: String,
    val port: Int,
    val url: String,
    val token: String? = null,
)

internal const val PORT_TYPE_MCP_STEROID = "mcp-steroid"
internal const val PORT_TYPE_BUILTIN_HTTP = "builtin-http"
