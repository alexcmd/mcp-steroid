/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Handler for the steroid_list_projects MCP tool.
 */
class ListProjectsToolHandler : McpTool {
    override val name = "steroid_list_projects"
    override val description = "List all open projects in the IDE. Returns project names that can be used with steroid_execute_code and steroid_open_project."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
        putJsonArray("required") { }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val response = collectListProjectsResponse()
        val json = McpJson.encodeToString(response)

        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json))
        )
    }
}

suspend fun collectListProjectsResponse(): ListProjectsResponse {
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
        projects = projects
    )
}

@Serializable
data class ListProjectsResponse(
    val ide: IdeInfo = IdeInfo.ofApplication(),
    val plugin: PluginInfo = PluginInfo.ofCurrentPlugin(),
    val pid: Long = ProcessHandle.current().pid(),
    val projects: List<ProjectInfo>
)


@Serializable
data class ProjectInfo(
    val name: String,
    val path: String
)
