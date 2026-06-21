/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.devrig.BackendInventory
import com.jonnyzzz.mcpSteroid.devrig.BackendVersionSkew
import com.jonnyzzz.mcpSteroid.devrig.DevrigBeacon
import com.jonnyzzz.mcpSteroid.devrig.collectBackendInfos
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import com.jonnyzzz.mcpSteroid.server.InputParams
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.ScreenshotParams
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import com.jonnyzzz.mcpSteroid.server.listed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put

class DevrigListWindowsToolHandler(
    private val states: () -> Collection<IdeMonitorState>,
    private val bridge: DevrigToolBridgeClient,
    private val inventory: BackendInventory,
) : ListWindowsToolHandler {
    override suspend fun collectListWindowsResponse(): ListWindowsResponse = coroutineScope {
        val monitored = states().toList()
        val responses = monitored.map { state ->
            async { state to bridge.fetchWindows(state.ide) }
        }.awaitAll()

        // One snapshot of every routable project, keyed by (owning IDE pid, raw project name). Each
        // window/background-task is rewritten to that route's devrig-exposed project_name so it matches
        // what steroid_list_projects surfaces. The backend_name binds the entry to its source IDE — the
        // same R3.3 id the inventory computes for that IDE's marker row, so entries join backends[] by name.
        val routesByOwner = bridge.routing.routes().values
            .associateBy { it.idePid to it.originalProjectName }

        fun exposedProjectName(idePid: Long, rawProjectName: String?): String? =
            rawProjectName?.let { routesByOwner[idePid to it]?.exposedProjectName ?: it }

        ListWindowsResponse(
            windows = responses.flatMap { (state, response) ->
                response.windows.map { window ->
                    window.listed(exposedProjectName(state.ide.pid, window.projectName), state.ide.backendName)
                }
            },
            backgroundTasks = responses.flatMap { (state, response) ->
                response.backgroundTasks.map { task ->
                    task.listed(exposedProjectName(state.ide.pid, task.projectName), state.ide.backendName)
                }
            },
            backends = inventory.collectBackendInfos(),
        )
    }
}

class DevrigExecuteCodeToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val beacon: DevrigBeacon,
) : ExecuteCodeToolHandler {
    override suspend fun executeCode(
        projectName: String,
        execCodeParams: ExecCodeParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        // Version-base skew check on every routed exec_code call (devrig scenario only; stderr).
        BackendVersionSkew.warnOnExecCode(pid = route.idePid, pluginVersion = route.plugin.version)
        val result = bridge.callProjectTool(route, "steroid_execute_code", callProgress) {
            put("code", execCodeParams.code)
            put("task_id", execCodeParams.taskId)
            put("reason", execCodeParams.reason)
            put("timeout", execCodeParams.timeout)
            put("modal", execCodeParams.modal.wire)
        }
        beacon.capture("exec_code", mapOf("result" to if (result.isError) "error" else "success"))
        return result
    }
}

class DevrigExecuteFeedbackToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val beacon: DevrigBeacon,
) : ExecuteFeedbackToolHandler {
    override suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        val result = bridge.callProjectTool(route, "steroid_execute_feedback") {
            put("task_id", params.taskId)
            put("success_rating", params.successRating)
            params.explanation?.let { put("explanation", it) }
            params.code?.let { put("code", it) }
        }
        // Mirror the ij-plugin's status_score event: 0.0-1.0 rating -> 0-100 score.
        beacon.captureScore((params.successRating * 100).toInt(), context = "feedback")
        return result
    }
}

class DevrigVisionScreenshotToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : VisionScreenshotToolHandler {
    override suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter,
    ): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        return bridge.callProjectTool(route, "steroid_take_screenshot", mcpProgressReporter) {
            put("task_id", screenshotParams.taskId)
            put("reason", screenshotParams.reason)
            // window_id is unique within the IDE resolved by project_name; forward it as-is.
            screenshotParams.windowId?.let { put("window_id", it) }
        }
    }
}

class DevrigVisionInputToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : VisionInputToolHandler {
    override suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        val rawSequence = inputParams.rawSequence
            ?: return ToolCallResult.errorResult("Input sequence cannot be forwarded without the original sequence string")
        return bridge.callProjectTool(route, "steroid_input") {
            put("task_id", inputParams.taskId)
            put("reason", inputParams.reason)
            // window_id is unique within the IDE resolved by project_name; forward it as-is.
            put("window_id", inputParams.windowId)
            put("sequence", rawSequence)
        }
    }
}

class DevrigOpenProjectToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : OpenProjectToolHandler {
    override suspend fun handleOpenProject(openProjectParams: OpenProjectParams): ToolCallResult {
        val requestedBackend = openProjectParams.backendName?.trim()?.takeIf { it.isNotEmpty() }

        // When the agent named a backend (devrig surface, where backend_name is REQUIRED), resolve it to
        // that exact IDE. When it is absent (direct-handler / E2E callers pass null), keep the existing
        // auto-pick: prefer a devrig-managed backend, else the newest discovered IDE.
        // Off the call dispatcher: the managed-pid lookup scans the local backends dir + checks pid liveness.
        val ide = withContext(Dispatchers.IO) {
            if (requestedBackend != null) {
                bridge.routing.resolveBackend(requestedBackend)
            } else {
                bridge.routing.openProjectTargetIde()
            }
        }

        if (ide == null) {
            return if (requestedBackend != null) {
                // The id is now an opaque hash (R3.3) — no prefix to key a hint off. resolveBackend only ever
                // returns routable marker IDEs, so a miss means the requested name is not a currently routable
                // backend. Self-correct by listing the routable backend_names; the agent likely copied a
                // non-routable id (a port-only or not-yet-running managed backend) from `devrig backend --json`.
                val routable = bridge.routing.discoveredBackends().map { it.first }
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

