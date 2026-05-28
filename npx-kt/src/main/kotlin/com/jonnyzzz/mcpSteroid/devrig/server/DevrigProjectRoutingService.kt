/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorService
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.server.ProgressTaskInfo
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.WindowInfo
import java.nio.file.Path
import java.security.MessageDigest

class DevrigProjectRoutingService(
    private val stateProvider: () -> Map<Long, IdeMonitorState>,
) {
    constructor(ideMonitor: IdeMonitorService) : this({ ideMonitor.states.value })

    fun routes(): Map<String, ProjectRoute> {
        val routes = linkedMapOf<String, ProjectRoute>()
        for ((pid, state) in stateProvider()) {
            for (project in state.lastSnapshot) {
                val route = projectRoute(pid, state.ide, project)
                routes[route.exposedProjectName] = route
            }
        }
        return routes
    }

    fun routeProject(exposedProjectName: String): ProjectRoute? =
        routes()[exposedProjectName]

    fun requireProject(exposedProjectName: String): ProjectRoute =
        routeProject(exposedProjectName)
            ?: throw ProjectRouteNotFoundException(exposedProjectName)

    /**
     * Rewrites only the project name to its exposed form. The window id is left untouched:
     * it is unique within a single IDE and always travels together with project_name, so the
     * IDE is resolved via project_name and the original window_id is forwarded as-is.
     */
    fun rewriteWindow(idePid: Long, window: WindowInfo): WindowInfo {
        val route = routeForWindow(idePid, window) ?: return window
        return window.copy(
            projectName = window.projectName?.let { route.exposedProjectName },
        )
    }

    fun rewriteBackgroundTask(idePid: Long, task: ProgressTaskInfo): ProgressTaskInfo {
        val projectName = task.projectName ?: return task
        val route = routes().values.firstOrNull {
            it.idePid == idePid && it.originalProjectName == projectName
        } ?: return task
        return task.copy(projectName = route.exposedProjectName)
    }

    fun singleRouteOrNull(): ProjectRoute? {
        val routes = routes().values.toList()
        return if (routes.size == 1) routes.single() else null
    }

    fun singleIdeOrNull(): DiscoveredIde? {
        val ides = stateProvider().values.map { it.ide }.distinctBy { it.pid }
        return if (ides.size == 1) ides.single() else null
    }

    private fun projectRoute(idePid: Long, ide: DiscoveredIde, project: ProjectInfo): ProjectRoute {
        val realHome = canonicalProjectHome(project.path)
        val projectHash = projectHash(realHome, idePid)
        return ProjectRoute(
            idePid = idePid,
            bridgeBaseUrl = bridgeBaseUrl(ide.mcpUrl),
            headers = ide.marker.mcpSteroidServer.headers,
            originalProjectName = project.name,
            exposedProjectName = "${project.name}-$projectHash",
            projectPath = project.path,
            realProjectHome = realHome,
            projectHash = projectHash,
            ide = ide.marker.ide,
            plugin = ide.marker.plugin,
        )
    }

    private fun routeForWindow(idePid: Long, window: WindowInfo): ProjectRoute? {
        val allRoutes = routes().values.filter { it.idePid == idePid }
        val projectPath = window.projectPath
        if (projectPath != null) {
            val realPath = canonicalProjectHome(projectPath)
            allRoutes.firstOrNull { it.realProjectHome == realPath }?.let { return it }
        }
        val projectName = window.projectName ?: return null
        return allRoutes.firstOrNull { it.originalProjectName == projectName }
    }

    companion object {
        fun canonicalProjectHome(projectHome: String): Path =
            Path.of(projectHome).toRealPath()

        private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        fun projectHash(realProjectHome: Path, idePid: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(realProjectHome.toString().encodeToByteArray())
            digest.update(0.toByte())
            digest.update(idePid.toString().encodeToByteArray())
            // base62 (alphanumeric) over the full digest, first 8 chars. Unlike URL-safe
            // Base64 the alphabet has no '-'/'_', so the suffix can never contain or end
            // with '-'; the whole 256-bit digest feeds the result, nothing is truncated first.
            var value = java.math.BigInteger(1, digest.digest())
            val base = java.math.BigInteger.valueOf(62L)
            val sb = StringBuilder(8)
            repeat(8) {
                val (q, r) = value.divideAndRemainder(base)
                sb.append(BASE62[r.toInt()])
                value = q
            }
            return sb.toString()
        }

        fun bridgeBaseUrl(mcpUrl: String): String =
            mcpUrl.trimEnd('/').removeSuffix("/mcp")
    }
}

data class ProjectRoute(
    val idePid: Long,
    val bridgeBaseUrl: String,
    val headers: Map<String, String>,
    val originalProjectName: String,
    val exposedProjectName: String,
    val projectPath: String,
    val realProjectHome: Path,
    val projectHash: String,
    val ide: IdeInfo,
    val plugin: PluginInfo,
)

class ProjectRouteNotFoundException(projectName: String) : IllegalArgumentException(
    "project_name '$projectName' is no longer present; call steroid_list_projects to refresh"
)
