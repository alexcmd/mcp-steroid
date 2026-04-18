/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Narrow role interface exposed by [McpToolRegistry] for registering a single MCP tool.
 * Passed to individual tool handlers so they only see the one method they need.
 */
fun interface McpToolRegistrar {
    fun registerTool(
        name: String,
        description: String?,
        inputSchema: JsonObject,
        handler: suspend (ToolCallContext) -> ToolCallResult,
    )
}

/**
 * Narrow role interface exposed by [McpResourceRegistry] for registering a single MCP resource.
 */
fun interface McpResourceRegistrar {
    fun registerResource(
        uri: String,
        name: String,
        description: String?,
        mimeType: String,
        contentProvider: () -> String,
    )
}

/**
 * Narrow role interface exposed by [McpPromptRegistry] for registering a single MCP prompt.
 */
fun interface McpPromptRegistrar {
    fun registerPrompt(
        prompt: Prompt,
        renderer: (PromptGetParams) -> PromptGetResult,
    )
}

/**
 * Narrow role interface exposed by [McpResourceRegistry] for reading a resource at runtime.
 * Separate from [McpResourceRegistrar] because tools that *read* resources at call time don't
 * need the ability to register new ones.
 */
fun interface McpResourceReader {
    fun readResource(uri: String): ResourceReadResult?
}
