/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.attic

import com.posthog.server.PostHog
import com.posthog.server.PostHogCaptureOptions
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class NpxBeacon(private val config: ProxyConfig) {
    private val beaconConfig = config.beacon
    private val proxyVersion = config.version ?: "0.1.0"
    private var distinctId: String? = null
    private var heartbeatJob: Job? = null
    private var lastDiscoverySignature: String? = null

    private val posthog: PostHogInterface? by lazy {
        if (!beaconConfig.enabled || beaconConfig.apiKey.isBlank()) return@lazy null
        try {
            val phConfig = PostHogConfig
                .builder(beaconConfig.apiKey)
                .host(beaconConfig.host)
                .flushIntervalSeconds(15)
                .flushAt(10)
                .maxQueueSize(50)
                .maxBatchSize(5)
                .preloadFeatureFlags(false)
                .remoteConfig(false)
                .sendFeatureFlagEvent(false)
                .build()
            PostHog.with(phConfig)
        } catch (e: Exception) {
            null
        }
    }

    private fun getOrCreateDistinctId(): String {
        distinctId?.let { return it }
        val file = File(beaconConfig.distinctIdFile)
        try {
            val existing = file.readText().trim()
            if (existing.isNotEmpty()) {
                distinctId = existing
                return existing
            }
        } catch (e: Exception) {
            // ignore, create new
        }
        val generated = UUID.randomUUID().toString()
        try {
            file.parentFile?.mkdirs()
            file.writeText("$generated\n", Charsets.UTF_8)
        } catch (e: Exception) {
            // ignore write failures
        }
        distinctId = generated
        return generated
    }

    fun capture(event: String, properties: Map<String, Any> = emptyMap()) {
        val ph = posthog ?: return
        try {
            val id = getOrCreateDistinctId()
            val opts = PostHogCaptureOptions.builder()
            opts.property("proxy_version", proxyVersion)
            opts.property("platform", System.getProperty("os.name") ?: "unknown")
            opts.property("arch", System.getProperty("os.arch") ?: "unknown")
            properties.forEach { (k, v) -> opts.property(k, v) }
            opts.appendFeatureFlags(false)
            opts.timestamp(LocalDateTime.now().toInstant(ZoneOffset.UTC))
            ph.capture(id, event, opts.build())
        } catch (e: Exception) {
            // Ignore beacon failures
        }
    }

    fun captureDiscoveryChanged(registry: ServerRegistry, reason: String) {
        if (posthog == null) return
        val summary = registry.servers.values
            .map { "${it.serverId}:${it.status}:${it.url}" }
            .sorted()
            .joinToString("|")
        if (summary == lastDiscoverySignature) return
        lastDiscoverySignature = summary

        val totalServers = registry.servers.size
        val onlineServers = registry.listOnlineServers().size
        capture(BeaconEvents.DISCOVERY_CHANGED, mapOf(
            "reason" to reason,
            "total_servers" to totalServers,
            "online_servers" to onlineServers,
            "offline_servers" to maxOf(0, totalServers - onlineServers)
        ))
    }

    fun startHeartbeat(scope: CoroutineScope, registry: ServerRegistry) {
        if (posthog == null) return
        if (heartbeatJob != null) return
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(beaconConfig.heartbeatIntervalMs)
                capture(BeaconEvents.HEARTBEAT, mapOf(
                    "online_servers" to registry.listOnlineServers().size,
                    "total_servers" to registry.servers.size
                ))
            }
        }
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            posthog?.flush()
            posthog?.close()
        } catch (e: Exception) {
            // Ignore shutdown failures
        }
    }
}
