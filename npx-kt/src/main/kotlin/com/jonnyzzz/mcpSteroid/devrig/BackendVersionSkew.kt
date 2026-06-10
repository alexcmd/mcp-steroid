/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import java.util.concurrent.ConcurrentHashMap

/**
 * R3.9 — version-skew warning. Users upgrade devrig and the in-IDE plugin independently, so when a
 * discovered backend's plugin version differs from this devrig's version we emit a one-line warning to
 * **stderr** (never stdout — that is the JSON-RPC channel for the stdio MCP server).
 *
 * De-duplicated per `(pid, pluginVersion)` for the lifetime of the process, so it fires once per backend
 * rather than on every discovery scan / tool call.
 */
object BackendVersionSkew {
    private val warned = ConcurrentHashMap.newKeySet<String>()

    /** Warn (once) if [ide]'s plugin version differs from the devrig version. */
    fun warnIfSkewed(ide: DiscoveredIde) {
        warnIfSkewed(pid = ide.pid, pluginVersion = ide.marker.plugin.version)
    }

    fun warnIfSkewed(
        pid: Long,
        pluginVersion: String,
        devrigVersion: String = DevrigVersionMetadata.getDevrigVersion(),
    ) {
        val plugin = pluginVersion.trim()
        if (plugin.isEmpty() || plugin == devrigVersion.trim()) return
        if (!warned.add("$pid:$plugin")) return
        System.err.println(
            "WARN: devrig $devrigVersion talking to MCP Steroid $plugin (pid $pid) — versions differ; " +
                "if behavior is odd, update both.",
        )
    }
}
