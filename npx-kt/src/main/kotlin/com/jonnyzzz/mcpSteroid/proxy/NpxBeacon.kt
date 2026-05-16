/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.posthog.server.PostHog
import com.posthog.server.PostHogCaptureOptions
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds

internal enum class NpxBeaconMode(
    val eventName: String,
    val wireValue: String,
) {
    INTERACTIVE("devrig-interactive", "interactive"),
    MCP("devrig-mcp", "mcp"),
}

internal interface NpxBeaconSink {
    fun capture(distinctId: String, event: String, properties: Map<String, Any>)
    fun close()
}

internal class PostHogNpxBeaconSink : NpxBeaconSink {
    private val log = LoggerFactory.getLogger(PostHogNpxBeaconSink::class.java)

    private val posthog: PostHogInterface? by lazy {
        try {
            val config = PostHogConfig
                .builder("phc_IP" + "tbj" + "wwy" + 9 + "YIGg0YNHNxYBePijvTvHEcKAjohah" + 6 + "obYW")
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

    override fun capture(distinctId: String, event: String, properties: Map<String, Any>) {
        val ph = posthog ?: return
        val opts = PostHogCaptureOptions.builder()
        properties.forEach { (key, value) -> opts.property(key, value) }
        opts.appendFeatureFlags(false)
        opts.timestamp(LocalDateTime.now().toInstant(ZoneOffset.UTC))
        ph.capture(distinctId, event, opts.build())
        ph.flush()
    }

    override fun close() {
        posthog?.flush()
        posthog?.close()
    }
}

internal class NpxBeacon(
    private val proxyVersion: String,
    private val userIdFile: Path = defaultUserIdFile(),
    private val sink: NpxBeaconSink = PostHogNpxBeaconSink(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val delayProvider: () -> Duration = { startupBeaconDelay() },
    private val periodicInterval: Duration = 30.minutes,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(NpxBeacon::class.java)
    private val started = AtomicBoolean(false)
    private val startedAtMillis = System.currentTimeMillis()

    fun startInteractive(invocation: String) {
        start(mode = NpxBeaconMode.INTERACTIVE, invocation = invocation, periodic = false)
    }

    fun startMcp() {
        start(mode = NpxBeaconMode.MCP, invocation = "mcp", periodic = true)
    }

    private fun start(mode: NpxBeaconMode, invocation: String, periodic: Boolean) {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            delay(delayProvider())
            sendEvent(mode = mode, invocation = invocation, timer = "startup")

            if (periodic) {
                while (isActive) {
                    delay(periodicInterval)
                    sendEvent(mode = mode, invocation = invocation, timer = "heartbeat")
                }
            }
        }
    }

    internal fun sendEvent(mode: NpxBeaconMode, invocation: String, timer: String) {
        try {
            sink.capture(
                distinctId = distinctId(),
                event = mode.eventName,
                properties = mapOf(
                    "schema" to 1,
                    "tool" to BRAND_NAME,
                    "mode" to mode.wireValue,
                    "invocation" to invocation,
                    "timer" to timer,
                    "uptime_ms" to (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0),
                    "proxy_version" to proxyVersion,
                ),
            )
        } catch (e: Exception) {
            log.debug("Beacon capture failed", e)
        }
    }

    private fun distinctId(): String {
        Files.createDirectories(userIdFile.parent)
        val existing = try {
            Files.readString(userIdFile).trim()
        } catch (e: java.nio.file.NoSuchFileException) {
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
        scope.cancel()
        sink.close()
    }

    companion object {
        fun defaultUserIdFile(): Path =
            Path.of(System.getProperty("user.home")).resolve(".mcp-steroid").resolve("devrig-user-id")
    }
}

internal fun startupBeaconDelay(): Duration =
    ThreadLocalRandom.current().nextLong(1_000L, 5_001L).milliseconds

internal fun beaconInvocation(mode: CliMode): String = when (mode) {
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
