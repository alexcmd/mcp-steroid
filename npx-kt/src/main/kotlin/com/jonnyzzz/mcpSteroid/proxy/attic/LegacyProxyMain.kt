/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.attic

import com.jonnyzzz.mcpSteroid.proxy.loadProxyVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Legacy entry point — boots the old discovery/proxy stack (ServerRegistry +
 * NpxBeacon + StdioServer). Not wired into the production launcher.
 */
@Suppress("unused")
internal fun legacyProxyMain(args: Array<String>) {
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
                // Ignore update-check failures in CLI mode.
            }
            runCliMode(cliArgs, registry, traffic, beacon)
            beacon.shutdown()
            return@runBlocking
        }

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

        scope.launch {
            while (isActive) {
                delay(config.scanIntervalMs)
                try {
                    registry.refreshDiscovery()
                    beacon.captureDiscoveryChanged(registry, "interval")
                } catch (e: Exception) {
                    // Ignore refresh failures.
                }
            }
        }

        beacon.startHeartbeat(scope, registry)

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
            beacon.capture(
                BeaconEvents.UPGRADE_RECOMMENDED, mapOf(
                    "current_version" to extractBaseVersion(config.version ?: "")
                )
            )
        }
    } finally {
        upgradeCheckInFlight = false
    }
}

private suspend fun runUpgradeCheck(registry: ServerRegistry, config: ProxyConfig, beacon: NpxBeacon) {
    try {
        emitUpgradeNotice(registry, config, beacon)
    } catch (e: Exception) {
        // Ignore periodic update-check failures.
    }
}
