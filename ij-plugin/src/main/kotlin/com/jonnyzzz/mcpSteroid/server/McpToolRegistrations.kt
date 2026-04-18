/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

/**
 * Central, explicit registration of every MCP tool, resource, and prompt this plugin exposes.
 *
 * Replaces the previous `mcpRegistrar` extension point: tools are wired directly here, each
 * handler receiving only the narrow single-method interface(s) it needs from the server
 * (e.g. [com.jonnyzzz.mcpSteroid.mcp.McpToolRegistrar], [com.jonnyzzz.mcpSteroid.mcp.McpResourceRegistrar]).
 */
class McpToolRegistrations {
    fun registerAll(server: McpServerCore) {
        val tools = server.toolRegistry
        val resources = server.resourceRegistry
        val prompts = server.promptRegistry

        ListProjectsToolHandler().register(tools)
        ListWindowsToolHandler().register(tools)
        ExecuteCodeToolHandler().register(tools)
        ApplyPatchToolHandler().register(tools)
        ExecuteFeedbackToolHandler().register(tools)
        ActionDiscoveryToolHandler().register(tools)
        VisionScreenshotToolHandler().register(tools)
        VisionInputToolHandler().register(tools)
        OpenProjectToolHandler().register(tools)
        FetchResourceToolHandler(resources).register(tools)

        ResourceRegistrar().register(resources, prompts)
    }
}
