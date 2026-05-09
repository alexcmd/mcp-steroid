/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

/**
 * Central, explicit registration of every MCP tool, resource, and prompt this plugin exposes.
 *
 * Each tool handler is an [com.jonnyzzz.mcpSteroid.mcp.McpTool] implementation and is
 * registered by passing the instance itself to the narrow
 * [com.jonnyzzz.mcpSteroid.mcp.McpToolRegistrar] interface exposed by the server.
 */
class McpToolRegistrations {
    fun registerAll(server: McpServerCore) {
        val tools = server.toolRegistry
        val resources = server.resourceRegistry
        val prompts = server.promptRegistry

        tools.registerTool(ListProjectsToolSpec { service<ListProjectsToolHandler>() })
        tools.registerTool(ListWindowsToolSpec { service<ListWindowsToolHandler>() })
        tools.registerTool(ExecuteCodeToolSpec { service<ExecuteCodeToolHandler>() })
        tools.registerTool(ApplyPatchToolSpec { service<ApplyPatchToolHandler>() })
        tools.registerTool(ExecuteFeedbackToolSpec { service<ExecuteFeedbackToolHandler>() })
        tools.registerTool(ActionDiscoveryToolSpec { service<ActionDiscoveryToolHandler>() }) // deprecate it
        tools.registerTool(VisionScreenshotToolSpec { service<VisionScreenshotToolHandler>() })
        tools.registerTool(VisionInputToolSpec { service<VisionInputToolHandler>() })
        tools.registerTool(OpenProjectToolSpec { service<OpenProjectToolHandler>() })


        tools.registerTool(FetchResourceToolHandler { buildPromptsContext() })
        ResourceRegistrar { buildPromptsContext() }.register(resources, prompts)
    }
}
