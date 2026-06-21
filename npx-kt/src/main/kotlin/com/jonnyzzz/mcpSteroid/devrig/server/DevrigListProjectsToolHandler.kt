package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.BackendInventory
import com.jonnyzzz.mcpSteroid.devrig.collectListedBackends
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedProject

class DevrigListProjectsToolHandler(
    private val routing: DevrigProjectRoutingService,
    private val inventory: BackendInventory,
) : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val routes = routing.routes()
        val listedProjects = routes.map { route ->
            ListedProject(
                projectName = route.exposedProjectName,
                name = route.originalProjectName,
                path = route.projectPath,
                backendName = route.exposedBackendName,
            )
        }

        // backends[] = the whole inventory (markers + port-discovered + managed) through the ONE shared
        // BackendRow id + mapping, so the MCP `backends[]` and `devrig backend --json` never diverge on
        // which backends exist. projects[] stays marker-routed only (above).
        return ListProjectsResponse(
            projects = listedProjects,
            backends = inventory.collectListedBackends(),
        )
    }
}
