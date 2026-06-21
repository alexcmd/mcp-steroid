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

        val backends = routing.discoveredBackends()

        val ide = backends.firstOrNull { it.backendName == requestedBackend }
            ?: DevrigProjectRoutingService.newestOf(backends)
            ?: run {
                return ToolCallResult.errorResult(
                    "Unknown backend_name '$requestedBackend'. Only running IDEs with the MCP Steroid plugin " +
                        "are routable for open_project; port-only and not-yet-running managed backends listed by " +
                        "'devrig backend --json' are not. " +
                        if (backends.isEmpty()) "No routable IDE backends are currently discovered; start an IDE or call steroid_list_projects."
                        else "Routable backends: ${backends.joinToString { it.backendName }}. Call steroid_list_projects to refresh."
                )
            }

        return bridge.callTool(ide, "steroid_open_project") {
            put("project_path", openProjectParams.projectPath)
            put("trust_project", openProjectParams.trustProject)
            put("task_id", "open-project")
            put("reason", "Open project through devrig")
        }
    }
}
