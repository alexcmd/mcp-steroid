/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Writes the MCP server URL marker to the user home directory.
 *
 * The marker file is `~/.<pid>.mcp-steroid`, a JSON document defined by
 * [PidMarker]. External monitors (the `npx-kt` Kotlin proxy, the npm
 * `npx` proxy) read it to discover where this IDE's MCP server is
 * reachable. The `.idea/mcp-steroid.md` per-project description is
 * written separately by [IdeaDescriptionWriter].
 */
@Service(Service.Level.APP)
class ServerUrlWriter : Disposable {
    private val log = thisLogger()
    private var markerFile: Path? = null

    /**
     * Write the MCP server URL to the user home as a JSON marker file.
     * Stale marker files from dead processes are cleaned up first.
     *
     * The marker carries the IntelliJ-hosted MCP server's port (matches
     * [SteroidsMcpServer.port]) and bearer auth token (matches
     * [NpxBridgeService.token]) so external monitors can address the
     * server without parsing the URL or guessing the token.
     *
     * @param serverUrl The MCP server URL (e.g., "http://localhost:<port>/mcp")
     */
    fun writeServerUrlToUserHome(serverUrl: String) {
        val userHome = Path.of(System.getProperty("user.home"))

        cleanupStaleMarkerFiles(userHome)

        val pid = ProcessHandle.current().pid()
        val file = userHome.resolve(PidMarker.fileNameFor(pid))

        val marker = PidMarker(
            pid = pid,
            mcpUrl = serverUrl,
            port = SteroidsMcpServer.getInstance().port,
            token = NpxBridgeService.getInstance().token,
            ide = IdeInfo.ofApplication(),
            plugin = PluginInfo.ofCurrentPlugin(),
            createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            intellijMcpServer = IntelliJMcpServerProbe.getInstanceOrNull()?.probe(),
        )
        val content = PidMarkerJson.encode(marker)
        log.info("Writing MCP Steroid marker (pid=$pid)\n$content")

        try {
            Files.writeString(file, content)
            markerFile = file
            log.info("MCP Steroid marker file created: $file")
        } catch (e: Exception) {
            log.warn("Failed to create marker file in user home", e)
        }

        Disposer.register(this) {
            try {
                markerFile?.let { Files.deleteIfExists(it) }
            } catch (e: Exception) {
                log.warn("Failed to clean up MCP Steroid marker file on shutdown", e)
            }
        }
    }

    /**
     * Remove marker files left behind by IDE processes that no longer exist.
     * Matches both legacy text-format and current JSON-format markers — the
     * filename pattern is unchanged across the format switch.
     */
    private fun cleanupStaleMarkerFiles(userHome: Path) {
        try {
            Files.list(userHome).use { stream ->
                stream.filter { file ->
                    val pid = PidMarker.pidFromFileName(file.fileName.toString())
                    pid != null && !ProcessHandle.of(pid).isPresent
                }.forEach { staleFile ->
                    try {
                        Files.deleteIfExists(staleFile)
                        log.info("Removed stale MCP marker file: $staleFile")
                    } catch (e: Exception) {
                        log.warn("Failed to delete stale MCP marker file: $staleFile", e)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to cleanup stale marker files", e)
        }
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(): ServerUrlWriter = service()
    }
}
