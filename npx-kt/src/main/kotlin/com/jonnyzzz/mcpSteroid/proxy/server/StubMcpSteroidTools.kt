/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.proxy.NpxKtServices
import com.jonnyzzz.mcpSteroid.proxy.ProxyVersionMetadata
import com.jonnyzzz.mcpSteroid.server.ActionDiscoveryToolHandler
import com.jonnyzzz.mcpSteroid.server.ApplyPatchToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.PromptsContextHandler
import com.jonnyzzz.mcpSteroid.server.ResourceRegistrar
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler

class StubMcpSteroidTools(
    val services: NpxKtServices,
) : McpSteroidTools() {
    private val bridge = NpxToolBridgeClient(services.projectRouting, services.mcpHttpClient)
    private val listProjects = NpxListProjectsToolHandler(services)
    private val listWindows = NpxListWindowsToolHandler(services)
    private val promptsContext = NpxPromptsContextHandler(services.projectRouting)
    private val executeCode = NpxExecuteCodeToolHandler(bridge)
    private val applyPatch = NpxApplyPatchToolHandler(bridge)
    private val executeFeedback = NpxExecuteFeedbackToolHandler(bridge)
    private val actionDiscovery = NpxActionDiscoveryToolHandler(bridge)
    private val visionScreenshot = NpxVisionScreenshotToolHandler(bridge)
    private val visionInput = NpxVisionInputToolHandler(bridge)
    private val openProject = NpxOpenProjectToolHandler(services, bridge)

    override fun <T> handler(type: Class<T>): T {
        val handler = when (type) {
            ListProjectsToolHandler::class.java -> listProjects
            ListWindowsToolHandler::class.java -> listWindows
            PromptsContextHandler::class.java -> promptsContext
            ExecuteCodeToolHandler::class.java -> executeCode
            ApplyPatchToolHandler::class.java -> applyPatch
            ExecuteFeedbackToolHandler::class.java -> executeFeedback
            ActionDiscoveryToolHandler::class.java -> actionDiscovery
            VisionScreenshotToolHandler::class.java -> visionScreenshot
            VisionInputToolHandler::class.java -> visionInput
            OpenProjectToolHandler::class.java -> openProject
            else -> unsupportedHandler(type)
        }
        return type.cast(handler)
    }

    override fun registerExtra(server: McpServerCore) {
        ResourceRegistrar { promptsContext }.register(server.resourceRegistry, server.promptRegistry)
    }

    private fun unsupportedHandler(type: Class<*>): Nothing =
        throw UnsupportedOperationException(
            "not yet ready: handler<${type.name}>() is not wired in npx-kt yet for ${services.clientInfo.client}"
        )
}

class NpxListProjectsToolHandler(
    private val services: NpxKtServices,
) : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val routes = services.projectRouting.routes().values.toList()
        val first = routes.firstOrNull()
        return ListProjectsResponse(
            ide = first?.ide ?: IdeInfo("devrig", ProxyVersionMetadata.getProxyVersion(), "devrig"),
            plugin = first?.plugin ?: PluginInfo(
                "com.jonnyzzz.mcp-steroid.devrig",
                "devrig",
                ProxyVersionMetadata.getProxyVersion(),
            ),
            pid = first?.idePid ?: ProcessHandle.current().pid(),
            projects = routes.map { route ->
                ProjectInfo(
                    name = route.exposedProjectName,
                    path = route.projectPath,
                )
            },
        )
    }
}

class NpxPromptsContextHandler(
    private val routing: NpxProjectRoutingService,
) : PromptsContextHandler {
    override fun buildPromptsContext(projectName: String?): PromptsContext {
        if (projectName.isNullOrBlank()) return PromptsContext.Generic
        val route = routing.requireProject(projectName)
        return promptsContextFromBuild(route.ide.build)
    }

    companion object {
        fun promptsContextFromBuild(build: String): PromptsContext {
            val dash = build.indexOf('-')
            if (dash <= 0 || dash == build.lastIndex) return PromptsContext.Generic
            val productCode = build.substring(0, dash)
            val baseline = build.substring(dash + 1).substringBefore('.').toIntOrNull()
                ?: return PromptsContext.Generic
            return PromptsContext(productCode = productCode, baselineVersion = baseline)
        }
    }
}
