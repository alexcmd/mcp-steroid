/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeDiscoveryService
import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeMonitorService
import com.jonnyzzz.mcpSteroid.proxy.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.proxy.server.runStubStdioMcpServer
import com.jonnyzzz.mcpSteroid.server.NpxStreamClientInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

fun main(@Suppress("unused_parameter") args: Array<String>) {
    // STDOUT IS RESERVED FOR MCP NDJSON FRAMES.
    //
    // First action of the process: capture the real stdin / stdout, then
    // reroute the global `System.out` to stderr so any stray `println` from
    // application or third-party code (logback fallbacks before logback.xml
    // loads, kotlinx.coroutines warnings, posthog client noise, etc.) lands
    // on stderr instead of corrupting the JSON-RPC stream. The MCP transport
    // uses the saved-aside originals exclusively.
    //
    // Order matters: this redirect runs BEFORE any class touches a logger or
    // System.out — that's why it lives ahead of the runBlocking entry. The
    // stdout-cleanliness integration test pins this invariant.
    val mcpStdin = System.`in`
    val mcpStdout = System.out
    System.setOut(System.err)

    // Construct every dependent service explicitly here — the npx-kt module
    // does not use any DI framework, so main.kt is the wiring root.
    val proxyVersion = loadProxyVersion()
    val homeDir = File(System.getProperty("user.home"))
    val allowHosts = listOf("localhost", "127.0.0.1", "host.docker.internal")
    val clientInfo = NpxStreamClientInfo(
        client = "mcp-steroid-proxy (npx-kt)",
        clientPid = ProcessHandle.current().pid(),
        clientVersion = proxyVersion,
        clientInstanceId = "npx-kt-${UUID.randomUUID()}",
        platform = System.getProperty("os.name"),
        arch = System.getProperty("os.arch"),
    )

    // ktor-client used by the IDE-monitoring service. Long-lived NDJSON
    // streams need request/socket timeouts disabled; the connect timeout
    // stays bounded so an unreachable IDE doesn't hang the worker.
    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    val discovery = IdeDiscoveryService(homeDir = homeDir, allowHosts = allowHosts)
    val monitor = IdeMonitorService(
        httpClient = httpClient,
        discovery = discovery,
        clientInfo = clientInfo,
    )

    // Active port-scan discovery, independent of (and parallel to) the
    // marker-based path. Probes common IntelliJ HTTP-server ports on the
    // dedicated `mcp-steroid-port-scan-*` thread pool so a slow TCP
    // connect never stalls the stdio MCP server.
    val portDiscovery = IntelliJPortDiscovery(httpClient = httpClient)

    // npx-kt boots a real MCP stdio server backed by McpStdioServer +
    // McpSteroidTools (with stub handlers — see StubMcpSteroidTools). The legacy
    // proxy path that aggregates discovered IDE MCP servers lives in
    // [legacyProxyMain] and is intentionally unreachable; the source is retained
    // so the existing proxy classes keep compiling.
    //
    // Alongside the stdio server, the new IDE monitor runs:
    //   discovery → reads .<pid>.mcp-steroid JSON markers from $HOME
    //   monitor   → opens one POST /npx/v1/projects/stream per IDE,
    //               receives push notifications on project open/close
    runBlocking {
        coroutineScope {
            val discoveryJob = discovery.start(this)
            val monitorJob = monitor.start(this)
            val portDiscoveryJob = portDiscovery.start(this)
            try {
                runStubStdioMcpServer(input = mcpStdin, output = mcpStdout)
            } finally {
                portDiscoveryJob.cancel()
                monitorJob.cancel()
                discoveryJob.cancel()
                portDiscovery.close()
                httpClient.close()
            }
        }
    }
}

/**
 * Legacy entry point — boots the discovery/proxy stack (ServerRegistry +
 * NpxBeacon + the old StdioServer). Not wired into [main]; kept to retain
 * compile-time references to the proxy classes during the migration so unit
 * tests under `src/test/kotlin/.../proxy/` keep building.
 */
@Suppress("unused")
private fun legacyProxyMain(args: Array<String>) {
    val cliArgs = parseArgs(args.toList())

    if (cliArgs.help) {
        System.err.print(
            """
            Usage: mcp-steroid-proxy [--config path] [--scan-interval ms] [--log-traffic] [--cli]

            Default mode:
              stdio MCP server over stdin/stdout

            CLI mode:
              --cli [--cli-method <method> --cli-params-json '<json>']
              --cli --tool <toolName> [--arguments-json '<json>']
              --cli --uri <resourceUri>

            CLI defaults to --cli-method tools/list when no CLI selector is provided.
            """.trimIndent() + "\n"
        )
        return
    }

    val config = loadConfig(cliArgs).also { it.version = loadProxyVersion() }
    val traffic = TrafficLogger(config)
    val registry = ServerRegistry(config, traffic)
    val beacon = NpxBeacon(config)

    runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        beacon.capture(BeaconEvents.STARTED, mapOf("mode" to cliArgs.mode))

        registry.refreshDiscovery()
        beacon.captureDiscoveryChanged(registry, "startup")

        if (cliArgs.mode == "cli") {
            try {
                emitUpgradeNotice(registry, config, beacon)
            } catch (e: Exception) {
                // Ignore update-check failures in CLI mode
            }
            runCliMode(cliArgs, registry, traffic, beacon)
            beacon.shutdown()
            return@runBlocking
        }

        // Schedule update checks
        if (config.updates.enabled) {
            scope.launch {
                delay(config.updates.initialDelayMs)
                runUpgradeCheck(registry, config, beacon)
                while (isActive) {
                    delay(config.updates.intervalMs)
                    runUpgradeCheck(registry, config, beacon)
                }
            }
        }

        // Periodic discovery refresh
        scope.launch {
            while (isActive) {
                delay(config.scanIntervalMs)
                try {
                    registry.refreshDiscovery()
                    beacon.captureDiscoveryChanged(registry, "interval")
                } catch (e: Exception) {
                    // Ignore refresh failures
                }
            }
        }

        beacon.startHeartbeat(scope, registry)

        // Run stdio server (blocks until stdin closes)
        val server = StdioServer(registry, traffic, beacon)
        server.run()

        beacon.shutdown()
    }
}

private var upgradeNoticeShown = false
private var upgradeCheckInFlight = false

private suspend fun emitUpgradeNotice(registry: ServerRegistry, config: ProxyConfig, beacon: NpxBeacon) {
    if (upgradeCheckInFlight) return
    upgradeCheckInFlight = true
    try {
        val notice = buildUpgradeNotice(registry, config)
        if (notice != null && !upgradeNoticeShown) {
            upgradeNoticeShown = true
            System.err.write("$notice\n".toByteArray(Charsets.UTF_8))
            System.err.flush()
            beacon.capture(BeaconEvents.UPGRADE_RECOMMENDED, mapOf(
                "current_version" to extractBaseVersion(config.version ?: "")
            ))
        }
    } finally {
        upgradeCheckInFlight = false
    }
}

private suspend fun runUpgradeCheck(registry: ServerRegistry, config: ProxyConfig, beacon: NpxBeacon) {
    try {
        emitUpgradeNotice(registry, config, beacon)
    } catch (e: Exception) {
        // Ignore periodic update-check failures
    }
}
