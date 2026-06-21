package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.BackendInventory
import com.jonnyzzz.mcpSteroid.devrig.collectBackendInfos
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.listed
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class DevrigListWindowsToolHandler(
    private val states: () -> Collection<IdeMonitorState>,
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
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
        val routesByOwner = routing.routes().values
            .associateBy { it.route.pid to it.originalProjectName }

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
