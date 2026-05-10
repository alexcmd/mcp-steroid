/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.monitor

import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One IDE instance discovered through a `~/.<pid>.mcp-steroid` JSON marker.
 * Equality is keyed off the marker contents that affect the connection
 * (pid + mcpUrl) so [IdeDiscoveryService] can compare snapshots cheaply.
 */
data class DiscoveredIde(
    val pid: Long,
    val mcpUrl: String,
    /** Filename of the marker, useful when logging which file we picked up. */
    val markerPath: String,
    /** The full decoded marker — kept around for the IDE's [PidMarker.ide] / [PidMarker.plugin] metadata. */
    val marker: PidMarker,
) {
    /** Stable, human-friendly identifier used in logs (`IntelliJ IDEA pid=12345`). */
    val label: String
        get() = "${marker.ide.name} pid=$pid"
}

/**
 * Polling discovery layer for `~/.<pid>.mcp-steroid` JSON markers.
 *
 * The legacy proxy ([com.jonnyzzz.mcpSteroid.proxy.scanMarkers]) does the
 * same thing but is locked into [com.jonnyzzz.mcpSteroid.proxy.MarkerEntry];
 * this service exposes a typed [DiscoveredIde] flow and is the single
 * source of truth for the new monitor stack.
 *
 * Behaviour:
 *  - Scans [homeDir] every [scanInterval] and emits a fresh value on the
 *    flow whenever the discovered set changes (by [DiscoveredIde] equality).
 *  - Skips markers whose host is not in [allowHosts] — the same allowlist
 *    discipline as the legacy proxy.
 *  - Skips markers whose pid is no longer alive.
 *  - Tolerates malformed JSON: a single corrupt marker is logged and
 *    excluded; the rest of the scan continues.
 */
class IdeDiscoveryService(
    private val homeDir: File,
    private val allowHosts: List<String>,
    private val scanInterval: Duration = 2.seconds,
) {
    private val log = LoggerFactory.getLogger(IdeDiscoveryService::class.java)

    private val _ides = MutableStateFlow<Set<DiscoveredIde>>(emptySet())
    val ides: StateFlow<Set<DiscoveredIde>> = _ides.asStateFlow()

    fun start(scope: CoroutineScope): Job = scope.launch {
        // Emit an initial snapshot synchronously so consumers don't have to wait
        // a full scanInterval before the first value lands on the flow.
        scanOnce()
        while (isActive) {
            delay(scanInterval)
            scanOnce()
        }
    }

    /** One-shot scan — exposed for test code and for manual refresh after a connection error. */
    fun scanOnce() {
        val discovered = scanCurrent()
        _ides.value = discovered
    }

    private fun scanCurrent(): Set<DiscoveredIde> {
        val files = homeDir.listFiles() ?: return emptySet()
        val out = mutableSetOf<DiscoveredIde>()
        for (file in files) {
            if (!file.isFile) continue
            val pid = PidMarker.pidFromFileName(file.name) ?: continue
            if (!ProcessHandle.of(pid).isPresent) continue
            val text = try { file.readText() } catch (e: Exception) {
                log.warn("Failed to read marker file {}: {}", file.absolutePath, e.message)
                continue
            }
            val marker = try {
                PidMarkerJson.decode(text)
            } catch (e: Exception) {
                log.warn("Skipping malformed marker file {}: {}", file.absolutePath, e.message)
                continue
            }
            if (!isAllowedHost(marker.mcpUrl)) {
                log.debug("Skipping marker {} — host not in allowlist", file.absolutePath)
                continue
            }
            out += DiscoveredIde(
                pid = pid,
                mcpUrl = marker.mcpUrl,
                markerPath = file.absolutePath,
                marker = marker,
            )
        }
        return out
    }

    private fun isAllowedHost(url: String): Boolean = try {
        val host = URI(url).host ?: return false
        allowHosts.contains(host)
    } catch (e: Exception) {
        false
    }
}
