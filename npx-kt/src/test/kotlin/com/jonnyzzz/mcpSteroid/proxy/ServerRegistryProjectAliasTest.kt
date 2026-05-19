/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRegistryProjectAliasTest {
    @Test
    fun `project mappings expose public names with project path and IDE hashes`() {
        val registry = registryWithProjects(
            server("server-a", ideVersion = "2025.3", ideBuild = "IU-253.1", projectPath = "/work/shop-a"),
            server("server-b", ideVersion = "2026.1", ideBuild = "IU-261.1", projectPath = "/work/shop-b"),
        )

        val first = registry.projectMappings.single { it.serverId == "server-a" }
        val second = registry.projectMappings.single { it.serverId == "server-b" }

        assertEquals("shop", first.projectName)
        assertEquals("shop", second.projectName)
        assertNotEquals(first.publicProjectName, second.publicProjectName)
        assertTrue(first.publicProjectName.startsWith("shop--p"), first.publicProjectName)
        assertTrue(first.publicProjectName.contains("--i"), first.publicProjectName)
        assertEquals(
            proxyProjectName(
                projectName = "shop",
                projectPath = "/work/shop-a",
                ide = IdeMetadata(name = "IntelliJ IDEA", version = "2025.3", build = "IU-253.1"),
                serverId = "server-a",
            ),
            first.publicProjectName,
        )
    }

    @Test
    fun `public project alias routes to owning server and rewrites upstream args to raw project name`() {
        val registry = registryWithProjects(
            server("server-a", ideVersion = "2025.3", ideBuild = "IU-253.1", projectPath = "/work/shop-a"),
            server("server-b", ideVersion = "2026.1", ideBuild = "IU-261.1", projectPath = "/work/shop-b"),
        )
        val alias = registry.projectMappings.single { it.serverId == "server-b" }.publicProjectName

        val resolved = registry.resolveServerForToolCall("steroid_execute_code", buildJsonObject {
            put("project_name", alias)
        })

        assertEquals(RoutingResult.Resolved(serverId = "server-b", toolName = "steroid_execute_code"), resolved)

        val prepared = registry.prepareToolArgumentsForServer("server-b", buildJsonObject {
            put("server_id", "server-b")
            put("project_name", alias)
            put("reason", "test")
        })

        assertNull(prepared.error)
        assertEquals("shop", prepared.arguments["project_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("test", prepared.arguments["reason"]?.jsonPrimitive?.contentOrNull)
        assertNull(prepared.arguments["server_id"], "server_id is proxy-only and must not be sent upstream")
    }

    @Test
    fun `raw duplicate project name fails with aliases instead of picking freshest server`() {
        val registry = registryWithProjects(
            server("server-a", ideVersion = "2025.3", ideBuild = "IU-253.1", projectPath = "/work/shop-a"),
            server("server-b", ideVersion = "2026.1", ideBuild = "IU-261.1", projectPath = "/work/shop-b"),
        )
        val aliases = registry.projectMappings.map { it.publicProjectName }

        val resolved = registry.resolveServerForToolCall("steroid_execute_code", buildJsonObject {
            put("project_name", "shop")
        })

        val error = resolved as? RoutingResult.Error
        assertNotNull(error, "duplicate raw project names must be ambiguous")
        assertTrue(error.message.contains("Ambiguous project_name 'shop'"), error.message)
        for (alias in aliases) {
            assertTrue(error.message.contains(alias), "missing alias $alias in: ${error.message}")
        }
    }

    @Test
    fun `raw project name with project_path still routes to the matching server`() {
        val registry = registryWithProjects(
            server("server-a", ideVersion = "2025.3", ideBuild = "IU-253.1", projectPath = "/work/shop-a"),
            server("server-b", ideVersion = "2026.1", ideBuild = "IU-261.1", projectPath = "/work/shop-b"),
        )

        val resolved = registry.resolveServerForToolCall("steroid_execute_code", buildJsonObject {
            put("project_name", "shop")
            put("project_path", "/work/shop-b")
        })

        assertEquals(RoutingResult.Resolved(serverId = "server-b", toolName = "steroid_execute_code"), resolved)
    }

    @Test
    fun `public alias collision asks for explicit server id`() {
        val registry = registryWithProjects(
            server("server-a", ideVersion = "2025.3", ideBuild = "IU-253.1", projectPath = "/work/shop"),
            server("server-b", ideVersion = "2025.3", ideBuild = "IU-253.1", projectPath = "/work/shop"),
        )
        val alias = registry.projectMappings.first().publicProjectName
        assertEquals(1, registry.projectMappings.map { it.publicProjectName }.toSet().size)

        val resolved = registry.resolveServerForToolCall("steroid_execute_code", buildJsonObject {
            put("project_name", alias)
        })

        val error = resolved as? RoutingResult.Error
        assertNotNull(error, "duplicate aliases must not silently pick a server")
        assertTrue(error.message.contains("Ambiguous proxy project_name"), error.message)
        assertTrue(error.message.contains("server-a"), error.message)
        assertTrue(error.message.contains("server-b"), error.message)
    }

    @Test
    fun `alias from another server is rejected before upstream dispatch`() {
        val registry = registryWithProjects(
            server("server-a", ideVersion = "2025.3", ideBuild = "IU-253.1", projectPath = "/work/shop-a"),
            server("server-b", ideVersion = "2026.1", ideBuild = "IU-261.1", projectPath = "/work/shop-b"),
        )
        val alias = registry.projectMappings.single { it.serverId == "server-a" }.publicProjectName

        val prepared = registry.prepareToolArgumentsForServer("server-b", buildJsonObject {
            put("project_name", alias)
        })

        val error = prepared.error
        assertNotNull(error)
        assertTrue(error.contains("belongs to server-a"), error)
        assertTrue(error.contains("not server-b"), error)
    }

    private data class ServerSpec(
        val serverId: String,
        val ideVersion: String,
        val ideBuild: String,
        val projectPath: String,
    )

    private fun server(
        serverId: String,
        ideVersion: String,
        ideBuild: String,
        projectPath: String,
    ) = ServerSpec(serverId, ideVersion, ideBuild, projectPath)

    private fun registryWithProjects(vararg specs: ServerSpec): ServerRegistry {
        val config = ProxyConfig(scanIntervalMs = Long.MAX_VALUE)
        val registry = ServerRegistry(config, TrafficLogger(config))

        for ((index, spec) in specs.withIndex()) {
            registry.servers[spec.serverId] = ServerEntry(
                serverId = spec.serverId,
                pid = 10_000L + index,
                url = "http://127.0.0.1:${4000 + index}/mcp",
                baseUrl = "http://127.0.0.1:${4000 + index}",
                bridgeBaseUrl = null,
                port = 4000 + index,
                label = spec.serverId,
                markerPath = "/tmp/${spec.serverId}.mcp-steroid",
                status = "online",
                lastSeenAt = "2026-04-27T00:00:0${index}Z",
            )
            registry.applyMetadata(
                spec.serverId,
                ServerMetadata(
                    ide = IdeMetadata(name = "IntelliJ IDEA", version = spec.ideVersion, build = spec.ideBuild),
                ),
            )
            registry.updateServerProjects(
                spec.serverId,
                projects = listOf(project("shop", spec.projectPath)),
            )
        }

        registry.rebuildProjectIndexFromCaches()
        return registry
    }

    private fun project(name: String, path: String): JsonObject = buildJsonObject {
        put("name", name)
        put("path", path)
    }
}
