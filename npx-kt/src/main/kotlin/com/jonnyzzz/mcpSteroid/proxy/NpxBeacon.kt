/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.logger
import com.posthog.server.PostHog
import com.posthog.server.PostHogCaptureOptions
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

class NpxBeacon(
    private val homePaths: HomePaths,
    private val eventSender: ((String, String, Map<String, Any>) -> Unit)? = null,
    private val startupDelayMillis: () -> Long = { ThreadLocalRandom.current().nextLong(1_000L, 5_001L) },
) : AutoCloseable {
    private val log = logger<NpxBeacon>()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val startedAtMillis = System.currentTimeMillis()

    private val posthogDelegate = lazy {
        try {
            val config = PostHogConfig
                .builder("phc_IPtbjwwy9YIGg0YNHNxYBePijvTvHEcKAjohah6obYW")
                .host("https://us.i.posthog.com")
                .flushIntervalSeconds(15)
                .flushAt(10)
                .maxQueueSize(50)
                .maxBatchSize(5)
                .preloadFeatureFlags(false)
                .remoteConfig(false)
                .sendFeatureFlagEvent(false)
                .build()

            PostHog.with(config)
        } catch (e: Exception) {
            log.debug("PostHog init failed", e)
            null
        }
    }
    private val posthog: PostHogInterface? by posthogDelegate

    fun startInteractive(invocation: String) {
        start(
            event = "devrig-interactive",
            properties = mapOf(
                "mode" to "interactive",
                "invocation" to invocation,
            ),
        )
    }

    fun startMcp() {
        start(
            event = "devrig-mcp",
            properties = mapOf(
                "mode" to "mcp",
                "invocation" to "mcp",
            ),
        )
    }

    fun capture(event: String, properties: Map<String, Any> = emptyMap()) {
        Thread.ofVirtual().name("devrig-beacon").start {
            sendEventInternal(event, properties)
        }
    }

    internal fun sendEventInternal(event: String, properties: Map<String, Any>) {
        if (closed.get()) return

        try {
            val enrichedProperties = LinkedHashMap<String, Any>()
            enrichedProperties.putAll(properties)
            enrichedProperties["tool"] = BRAND_NAME
            enrichedProperties["timer"] = properties["timer"] ?: "manual"
            enrichedProperties["uptime_ms"] = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0)
            enrichedProperties["proxy_version"] = ProxyVersionMetadata.getProxyVersion()

            val distinctId = distinctId()
            val sender = eventSender
            if (sender != null) {
                sender(distinctId, event, enrichedProperties)
                return
            }

            val ph = posthog ?: return
            val opts = PostHogCaptureOptions.builder()
            enrichedProperties.forEach { (key, value) -> opts.property(key, value) }
            opts.appendFeatureFlags(false)
            opts.timestamp(LocalDateTime.now().toInstant(ZoneOffset.UTC))
            ph.capture(distinctId, event, opts.build())
            ph.flush()
        } catch (e: Exception) {
            log.debug("Beacon capture failed", e)
        }
    }

    private fun start(event: String, properties: Map<String, Any>) {
        if (!started.compareAndSet(false, true)) return

        Thread.ofVirtual().name("devrig-beacon-startup").start {
            try {
                Thread.sleep(startupDelayMillis())
                sendEventInternal(event, properties + ("timer" to "startup"))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                log.debug("Beacon startup failed", e)
            }
        }
    }

    private fun distinctId(): String {
        val userIdFile = homePaths.home.resolve("devrig-user-id")
        Files.createDirectories(userIdFile.parent)

        val existing = try {
            Files.readString(userIdFile).trim()
        } catch (_: NoSuchFileException) {
            ""
        } catch (e: Exception) {
            log.debug("Could not read beacon user id from {}", userIdFile, e)
            ""
        }
        if (existing.isNotEmpty()) return existing

        val generated = UUID.randomUUID().toString()
        Files.writeString(userIdFile, "$generated\n")
        return generated
    }

    override fun close() {
        closed.set(true)
        if (posthogDelegate.isInitialized()) {
            posthog?.flush()
            posthog?.close()
        }
    }
}

fun beaconInvocation(mode: CliMode): String = when (mode) {
    CliMode.Mcp -> "mcp"
    CliMode.Help -> "help"
    CliMode.Version -> "version"
    is CliMode.Unknown -> "unknown"
    CliMode.Backend.Text, CliMode.Backend.Json -> "backend"
    is CliMode.Backend.DownloadList, is CliMode.Backend.Download -> "backend-download"
    is CliMode.Backend.StartList, is CliMode.Backend.Start -> "backend-start"
    is CliMode.Backend.StopList, is CliMode.Backend.Stop -> "backend-stop"
    is CliMode.Backend.ProvisionList, is CliMode.Backend.Provision -> "backend-provision"
    CliMode.Project.Text, CliMode.Project.Json -> "project"
}
