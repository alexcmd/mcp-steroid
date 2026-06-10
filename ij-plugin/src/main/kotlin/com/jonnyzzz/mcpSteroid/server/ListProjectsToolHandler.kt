/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo

/**
 * Direct in-IDE `steroid_list_projects`. R3.6 — the surface self-describes with the SAME shape devrig
 * emits: exactly one [BackendInfo] for this IDE (its own R3.3 `backend_name` over its pid; `source=marker`,
 * `routable=true`, `mcpSteroidPluginInstalled=true`), and one [ListedProject] per open project where
 * `project_name == name` and `backend_name` is this IDE's self-id.
 */
class ListProjectsToolHandlerIJ : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val ide = IdeInfo.ofApplication()
        val plugin = PluginInfo.ofCurrentPlugin()
        val pid = ProcessHandle.current().pid()
        val selfBackendName = backendNameForMarker(pid = pid, build = ide.build)

        val openProjects = readAction {
            ProjectManager.getInstance().openProjects.toList()
        }

        val listedProjects = openProjects.map { project ->
            val name = project.name
            ListedProject(
                projectName = name,
                name = name,
                path = project.basePath ?: "",
                backendName = selfBackendName,
            )
        }

        val selfBackend = BackendInfo(
            backendName = selfBackendName,
            source = "marker",
            displayName = "${ide.name} ${ide.version}",
            locator = "build ${ide.build}, pid $pid",
            routable = true,
            reachable = true,
            pid = pid,
            ideProductCode = productCodeFromBuild(ide.build),
            build = ide.build,
            mcpSteroidPluginInstalled = true,
            plugin = plugin,
            ide = ide,
            openProjects = listedProjects,
        )

        return ListProjectsResponse(
            ide = ide,
            plugin = plugin,
            pid = pid,
            projects = listedProjects,
            backends = listOf(selfBackend),
        )
    }
}
