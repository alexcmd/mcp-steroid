package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.BackendInventory
import com.jonnyzzz.mcpSteroid.devrig.BackendRow
import com.jonnyzzz.mcpSteroid.devrig.collectBackendInfos
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedProject

class DevrigListProjectsToolHandler(
    private val routing: DevrigProjectRoutingService,
    private val inventory: BackendInventory,
) : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val routes = routing.routes().values.toList()
        // Each route maps to its owning backend's backend_name so projects[] and backends[] agree.
        // discoveredBackends() de-dupes by backend_name (keep-first + WARN) — the same names the
        // inventory computes for its marker rows (both use backendNameForMarker(pid, build)).
        val backendNameByPid = routing.discoveredBackends().associate { (name, ide) -> ide.pid to name }
        val listedProjects = routes.mapNotNull { route ->
            val backendName = backendNameByPid[route.route.pid] ?: return@mapNotNull null
            ListedProject(
                projectName = route.exposedProjectName,
                name = route.originalProjectName,
                path = route.projectPath,
                backendName = backendName,
            )
        }
        // backends[] = ALL inventory rows (markers + port-discovered + managed) through the ONE
        // BackendRow -> BackendInfo mapping shared with the CLI, so the MCP `backends[]` and
        // `devrig backend --json` never diverge. Marker rows own their routes' projects; port/managed
        // rows surface with routable=false and no openProjects so the agent sees the full picture.
        val backends = inventory.collectBackendInfos { backendName, row ->
            when (row) {
                is BackendRow.FromMarker -> listedProjects.filter { it.backendName == backendName }
                is BackendRow.FromPort, is BackendRow.FromManaged -> emptyList()
            }
        }
        return ListProjectsResponse(
            projects = listedProjects,
            backends = backends,
        )
    }
}
