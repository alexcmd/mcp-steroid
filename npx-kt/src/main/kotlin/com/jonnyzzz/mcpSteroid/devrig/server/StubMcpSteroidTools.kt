/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.devrig.DevrigServices
import com.jonnyzzz.mcpSteroid.devrig.DevrigVersionMetadata
import com.jonnyzzz.mcpSteroid.devrig.markerBackendDisplayName
import com.jonnyzzz.mcpSteroid.devrig.markerBackendLocatorLabel
import com.jonnyzzz.mcpSteroid.devrig.productCodeFromBuild
import com.jonnyzzz.mcpSteroid.server.BackendInfo
import com.jonnyzzz.mcpSteroid.server.ListedProject
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolSpec
import com.jonnyzzz.mcpSteroid.server.PromptsContextHandler
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler

class StubMcpSteroidTools(
    val services: DevrigServices,
) : McpSteroidTools() {
    private val bridge = DevrigToolBridgeClient(services.projectRouting, services.mcpHttpClient)
    private val listProjects = DevrigListProjectsToolHandler(services.projectRouting)
    private val listWindows = DevrigListWindowsToolHandler(services)
    val promptsContext = DevrigPromptsContextHandler(services.projectRouting)
    private val executeCode = DevrigExecuteCodeToolHandler(bridge, services.beacon)
    private val executeFeedback = DevrigExecuteFeedbackToolHandler(bridge, services.beacon)
    private val visionScreenshot = DevrigVisionScreenshotToolHandler(bridge)
    private val visionInput = DevrigVisionInputToolHandler(bridge)
    private val openProject = DevrigOpenProjectToolHandler(bridge)

    override fun <T> handler(type: Class<T>): T {
        val handler = when (type) {
            ListProjectsToolHandler::class.java -> listProjects
            ListWindowsToolHandler::class.java -> listWindows
            PromptsContextHandler::class.java -> promptsContext
            ExecuteCodeToolHandler::class.java -> executeCode
            ExecuteFeedbackToolHandler::class.java -> executeFeedback
            VisionScreenshotToolHandler::class.java -> visionScreenshot
            VisionInputToolHandler::class.java -> visionInput
            OpenProjectToolHandler::class.java -> openProject
            else -> unsupportedHandler(type)
        }
        return type.cast(handler)
    }

    // Devrig routes to one of several discovered IDEs, so it advertises the optional `backend_name`
    // routing parameter (REQUIRED on this surface). The in-IDE plugin keeps the default single-backend
    // surface (no `backend_name`).
    override fun openProjectToolSpec(): McpToolBase =
        OpenProjectToolSpec(includeBackendName = true) { handler<OpenProjectToolHandler>() }

    private fun unsupportedHandler(type: Class<*>): Nothing =
        throw UnsupportedOperationException(
            "not yet ready: handler<${type.name}>() is not wired in devrig yet for ${services.clientInfo.client}"
        )
}

class DevrigListProjectsToolHandler(
    private val routing: DevrigProjectRoutingService,
) : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val routes = routing.routes().values.toList()
        val first = routes.firstOrNull()
        val managedPids = routing.managedBackendPids()
        // backends[] lists ONLY routable marker IDEs (the only ones with a live bridge) — never a dead id.
        // discoveredBackends() de-dupes by backend_name (keep-first + WARN); recompute it once.
        val discovered = routing.discoveredBackends()
        // Each route maps to its owning backend's backend_name so projects[] and backends[] agree.
        val backendNameByPid = discovered.associate { (name, ide) -> ide.pid to name }
        val listedProjects = routes.mapNotNull { route ->
            val backendName = backendNameByPid[route.idePid] ?: return@mapNotNull null
            ListedProject(
                projectName = route.exposedProjectName,
                name = route.originalProjectName,
                path = route.projectPath,
                backendName = backendName,
            )
        }
        val backends = discovered.map { (backendName, ide) ->
            BackendInfo(
                backendName = backendName,
                source = "marker",
                displayName = markerBackendDisplayName(ide),
                locator = markerBackendLocatorLabel(ide), // "build IU-261.x, pid 1234"
                routable = true,
                reachable = true,
                managed = ide.pid in managedPids,
                pid = ide.pid,
                ideProductCode = productCodeFromBuild(ide.marker.ide.build),
                build = ide.marker.ide.build,
                mcpSteroidPluginInstalled = true,
                plugin = ide.marker.plugin,
                ide = ide.marker.ide,
                openProjects = listedProjects.filter { it.backendName == backendName },
            )
        }
        return ListProjectsResponse(
            ide = first?.ide ?: IdeInfo("devrig", DevrigVersionMetadata.getDevrigVersion(), "devrig"),
            plugin = first?.plugin ?: PluginInfo(
                "com.jonnyzzz.mcp-steroid.devrig",
                "devrig",
                DevrigVersionMetadata.getDevrigVersion(),
            ),
            pid = first?.idePid ?: ProcessHandle.current().pid(),
            projects = listedProjects,
            backends = backends,
        )
    }
}

class DevrigPromptsContextHandler(
    private val routing: DevrigProjectRoutingService,
) : PromptsContextHandler {
    override suspend fun buildPromptsContext(projectName: String): PromptsContext {
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
