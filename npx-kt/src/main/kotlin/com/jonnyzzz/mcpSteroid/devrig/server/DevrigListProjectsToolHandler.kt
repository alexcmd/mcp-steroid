package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedBackendInfo
import com.jonnyzzz.mcpSteroid.server.ListedProject

class DevrigListProjectsToolHandler(
    private val routing: DevrigProjectRoutingService,
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

        val listedBackends = routes.map { it.route }
            .distinctBy { it.backendName }
            .map {
                ListedBackendInfo(
                    backendName = it.backendName,
                    displayName = it.ide.name,
                    build = it.ide.build,
                    version = it.ide.version,
                )
            }

        //TODO: startable backends?
        return ListProjectsResponse(
            projects = listedProjects,
            backends = listedBackends,
        )
    }
}
