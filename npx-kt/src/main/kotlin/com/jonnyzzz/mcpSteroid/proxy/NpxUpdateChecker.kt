/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal interface NpxVersionSource {
    suspend fun fetchVersionBase(currentVersion: String): String?
}

internal class HttpNpxVersionSource : NpxVersionSource {
    private val log = LoggerFactory.getLogger(HttpNpxVersionSource::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchVersionBase(currentVersion: String): String? {
        val url = "https://mcp-steroid.jonnyzzz.com/version.json"
        val client = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
        return try {
            val response = client.get(url) {
                header("Accept", "application/json")
                header("User-Agent", "MCP-Steroid-Npx/$currentVersion")
            }
            if (!response.status.isSuccess()) return null
            val info = json.decodeFromString<NpxVersionInfo>(response.bodyAsText())
            info.versionBase
        } catch (e: Exception) {
            log.debug("Update check failed", e)
            null
        } finally {
            client.close()
        }
    }
}

internal class NpxUpdateChecker(
    private val currentVersion: String,
    private val versionSource: NpxVersionSource = HttpNpxVersionSource(),
    private val err: PrintStream = System.err,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val delayProvider: () -> Duration = { startupBeaconDelay() },
    private val periodicInterval: Duration = 15.minutes,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(NpxUpdateChecker::class.java)
    private val notificationShown = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    fun startOneShot() {
        start(periodic = false)
    }

    fun startPeriodic() {
        start(periodic = true)
    }

    private fun start(periodic: Boolean) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            delay(delayProvider())
            do {
                try {
                    checkForUpdates()
                } catch (e: Exception) {
                    log.debug("Failed to check for updates", e)
                }

                if (periodic) delay(periodicInterval)
            } while (periodic && isActive)
        }
    }

    suspend fun checkForUpdates() {
        val remoteVersion = versionSource.fetchVersionBase(currentVersion)?.trim().orEmpty()
        if (remoteVersion.isEmpty()) return

        if (!currentVersion.startsWith(remoteVersion) && notificationShown.compareAndSet(false, true)) {
            err.println(updateNotice(currentVersion, remoteVersion))
        }
    }

    override fun close() {
        scope.cancel()
    }
}

internal fun updateNotice(currentVersion: String, newVersion: String): String =
    "A new version of $BRAND_NAME is available: $newVersion (current: ${extractBaseVersion(currentVersion)}). " +
        "Download: https://mcp-steroid.jonnyzzz.com/releases/"

internal fun extractBaseVersion(fullVersion: String): String =
    fullVersion.substringBefore("-SNAPSHOT").substringBefore('-')

@Serializable
private data class NpxVersionInfo(
    @kotlinx.serialization.SerialName("version-base")
    val versionBase: String,
)
