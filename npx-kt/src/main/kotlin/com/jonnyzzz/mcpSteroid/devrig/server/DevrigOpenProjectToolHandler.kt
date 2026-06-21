package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put

class DevrigOpenProjectToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
) : OpenProjectToolHandler {
    override suspend fun handleOpenProject(openProjectParams: OpenProjectParams): ToolCallResult {
        val requestedBackend = openProjectParams.backendName?.trim()?.takeIf { it.isNotEmpty() }

        // When the agent named a backend (devrig surface, where backend_name is REQUIRED), resolve it to
        // that exact IDE. When it is absent (direct-handler / E2E callers pass null), keep the existing
        // auto-pick: prefer a devrig-managed backend, else the newest discovered IDE.
        // Off the call dispatcher: the managed-pid lookup scans the local backends dir + checks pid liveness.
        val ide = withContext(Dispatchers.IO) {
            if (requestedBackend != null) {
                routing.resolveBackend(requestedBackend)
            } else {
                routing.openProjectTargetIde()
            }
        }

        if (ide == null) {
            return if (requestedBackend != null) {
                // The id is now an opaque hash (R3.3) — no prefix to key a hint off. resolveBackend only ever
                // returns routable marker IDEs, so a miss means the requested name is not a currently routable
                // backend. Self-correct by listing the routable backend_names; the agent likely copied a
                // non-routable id (a port-only or not-yet-running managed backend) from `devrig backend --json`.
                val routable = routing.discoveredBackends().map { it.first }
                ToolCallResult.errorResult(
                    "Unknown backend_name '$requestedBackend'. Only running IDEs with the MCP Steroid plugin " +
                        "are routable for open_project; port-only and not-yet-running managed backends listed by " +
                        "'devrig backend --json' are not. " +
                        if (routable.isEmpty()) "No routable IDE backends are currently discovered; start an IDE or call steroid_list_projects."
                        else "Routable backends: ${routable.joinToString(", ")}. Call steroid_list_projects to refresh."
                )
            } else {
                ToolCallResult.errorResult(
                    "steroid_open_project requires at least one discovered IDE with the MCP Steroid plugin; start an IDE or call steroid_list_projects"
                )
            }
        }

        return bridge.callTool(ide, "steroid_open_project") {
            put("project_path", openProjectParams.projectPath)
            put("trust_project", openProjectParams.trustProject)
            put("task_id", "open-project")
            put("reason", "Open project through devrig")
        }
    }
}
