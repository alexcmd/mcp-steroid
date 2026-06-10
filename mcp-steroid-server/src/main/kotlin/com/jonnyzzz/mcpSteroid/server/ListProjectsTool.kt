package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.SerialName
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
    /**
     * Projects reachable through this connection. On a direct in-IDE connection `project_name == name`
     * and `backend_name` is this IDE's self-id; on devrig `project_name` is the disambiguated exposed
     * name and `backend_name` is the owning discovered IDE.
     */
    val projects: List<ListedProject>,
    /**
     * Backends reachable through this connection. On a direct in-IDE connection exactly one entry (this
     * IDE); on devrig one entry per discovered IDE. The `backend_name` of any routable entry is a valid
     * argument for steroid_open_project.
     */
    val backends: List<BackendInfo> = emptyList(),
)

/**
 * The wire DTO carried over `/projects/stream` (devrig<->IDE). Pristine `{name, path}` — the per-project
 * backend reference lives on the MCP-only [ListedProject], never here.
 */
@Serializable
data class ProjectInfo(
    val name: String,
    val path: String,
)

/**
 * One shared backend-info schema (R3.4) backing both the MCP `steroid_list_projects` `backends[]` and the
 * devrig CLI `backend/project --json` `backends[]`. MCP/CLI-surface only — never crosses the
 * devrig<->IDE wire.
 */
@Serializable
data class BackendInfo(
    /** R3.3 uniform opaque id, e.g. "iu-9fk2a0xQ". == backend_name passed to steroid_open_project. */
    @SerialName("backend_name") val backendName: String,
    val type: String = "intellij",
    /** "marker" | "port" | "managed". */
    val source: String,
    val displayName: String,
    val locator: String,
    /** True when open_project-routable (a marker IDE with a live bridge). */
    val routable: Boolean,
    /** True when discovery-reachable. */
    val reachable: Boolean,
    val managed: Boolean = false,
    /** Listed for humans/disambiguation; NOT encoded into [backendName]. */
    val pid: Long? = null,
    val port: Int? = null,
    val ideProductCode: String? = null,
    val build: String? = null,
    @SerialName("mcpSteroidPluginInstalled")
    val mcpSteroidPluginInstalled: Boolean = false,
    /** Installed plugin id/name/version (marker) — preserved, never dropped. */
    val plugin: PluginInfo? = null,
    /** Marker-unreachable message (was backendEntryJson "error"). */
    val error: String? = null,
    /** Top-level provision actions (e.g. port provisioning); not nested under managed. */
    val actions: List<BackendAction> = emptyList(),
    /** Port-only identity extras (renamed from the colliding scalar `port`). */
    val portDetail: PortBackendDetail? = null,
    /** Managed-only extras. */
    val managedDetail: ManagedBackendDetail? = null,
    /** Marker identity (name/version/build). */
    val ide: IdeInfo? = null,
    val openProjects: List<ListedProject> = emptyList(),
)

@Serializable
data class BackendAction(
    val id: String,
    val label: String,
    val command: String,
    val argv: List<String> = emptyList(),
)

@Serializable
data class PortBackendDetail(
    val baseUrl: String? = null,
    val productName: String? = null,
    val productFullName: String? = null,
    val edition: String? = null,
    val baselineVersion: Int? = null,
    val buildNumber: String? = null,
)

@Serializable
data class ManagedBackendDetail(
    val managedId: String,
    val productKey: String,
    val productCode: String,
    val version: String,
    val buildNumber: String? = null,
    val state: String,
    val installPath: String,
    val cachePath: String,
    val runningPid: Long? = null,
)

@Serializable
data class ListedProject(
    /** devrig: exposed disambiguated name; IDE-direct: the real project name. */
    @SerialName("project_name") val projectName: String,
    /** Raw folder name (R3.7) — kept so existing `jq '.projects[].name'` consumers do not break. */
    val name: String,
    val path: String,
    /** Owning backend's [BackendInfo.backendName]; null only when unknown. */
    @SerialName("backend_name") val backendName: String? = null,
)
