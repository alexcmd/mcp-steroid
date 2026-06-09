package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.Serializable

/**
 * Handler for the steroid_list_projects MCP tool.
 */
class ListProjectsToolSpec(val handler: () -> ListProjectsToolHandler) : McpToolBase() {
    override val name = "steroid_list_projects"
    override val description = "List all open projects in the IDE. Returns project names that can be used with steroid_execute_code and steroid_open_project."

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val response = handler().collectListProjectsResponse()
        val json = McpJson.encodeToString(response)

        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json))
        )
    }
}

interface ListProjectsToolHandler {
    suspend fun collectListProjectsResponse(): ListProjectsResponse
}

@Serializable
data class ListProjectsResponse(
    val ide: IdeInfo,
    val plugin: PluginInfo,
    val pid: Long = ProcessHandle.current().pid(),
    val projects: List<ProjectInfo>,
    /**
     * Backends reachable through this connection. Empty on a direct in-IDE connection
     * (single backend). On devrig, one entry per routable discovered IDE; `id` values are valid
     * `backend_name` arguments for steroid_open_project.
     */
    val backends: List<BackendSummary> = emptyList(),
)


@Serializable
data class ProjectInfo(
    val name: String,
    val path: String,
    /** Stable backend id owning this project (devrig only; null on a direct connection). */
    val backend: String? = null,
)

@Serializable
data class BackendSummary(
    /** The routable backend id == backend_name (e.g. "pid-1234"). */
    val id: String,
    /** Human label, e.g. "IntelliJ IDEA 2026.1" — NOT unique across two same-product IDEs. */
    val displayName: String,
    /** Disambiguator when two IDEs share a displayName, e.g. "build IU-261.x, pid 1234". */
    val locator: String,
    /** IDE product code derived from the build prefix, e.g. "IU" / "PY" / "GO". */
    val ideProductCode: String,
    /** True if this is a devrig-managed backend (prefer it when no worktree match). */
    val managed: Boolean,
    /** Projects currently open in this backend (names + paths) — lets an agent pick by what's open. */
    val openProjects: List<BackendProjectRef> = emptyList(),
    val ide: IdeInfo,
)

@Serializable
data class BackendProjectRef(
    val name: String,
    val path: String,
)
