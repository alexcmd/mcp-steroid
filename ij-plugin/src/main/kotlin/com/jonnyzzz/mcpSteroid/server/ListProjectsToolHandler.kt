/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ProjectManager

@Service(Service.Level.APP)
class ListProjectsToolHandlerIJ : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val openProjects = readAction {
            ProjectManager.getInstance().openProjects.toList()
        }

        val projects = openProjects.map { project ->
            ProjectInfo(
                name = project.name,
                path = project.basePath ?: ""
            )
        }

        return ListProjectsResponse(
            ide = IdeInfo.ofApplication(),
            plugin = PluginInfo.ofCurrentPlugin(),
            pid = ProcessHandle.current().pid(),
            projects = projects
        )
    }
}
