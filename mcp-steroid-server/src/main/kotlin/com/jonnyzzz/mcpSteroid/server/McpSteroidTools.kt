package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase

abstract class McpSteroidTools {
    fun registerAll(server: McpServerCore) {
        val tools = server.toolRegistry

        tools.registerTool(ListProjectsToolSpec { handler<ListProjectsToolHandler>() })
        tools.registerTool(ListWindowsToolSpec { handler<ListWindowsToolHandler>() })
        tools.registerTool(ExecuteCodeToolSpec { handler<ExecuteCodeToolHandler>() })
        tools.registerTool(ExecuteFeedbackToolSpec { handler<ExecuteFeedbackToolHandler>() })
        tools.registerTool(VisionScreenshotToolSpec { handler<VisionScreenshotToolHandler>() })
        tools.registerTool(VisionInputToolSpec { handler<VisionInputToolHandler>() })
        tools.registerTool(openProjectToolSpec())
        tools.registerTool(FetchResourceToolHandler { handler<PromptsContextHandler>() })
    }

    /**
     * The open_project tool spec. Overridable so devrig can advertise the optional `backend_name`
     * routing parameter while the in-IDE plugin keeps a single-backend surface (no `backend_name`).
     */
    protected open fun openProjectToolSpec(): McpToolBase =
        OpenProjectToolSpec { handler<OpenProjectToolHandler>() }

    inline fun <reified T : Any> handler(): T = handler(T::class.java)
    abstract fun <T> handler(type: Class<T>): T
}
