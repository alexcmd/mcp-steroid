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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Resolved CLI input + the captured stdio streams reserved for the MCP transport.
 *
 * Constructed exclusively by [main] on the `--mcp` branch — the [args] field carries
 * the original argv so [mainImpl] (and any future MCP-side feature flag) can read it
 * without re-parsing.
 */
internal data class MainContext(
    val args: List<String>,
    val mcpStdin: InputStream,
    val mcpStdout: PrintStream,
    val homePaths: HomePaths,
)

private val LOG_SESSION_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

fun main(args: Array<String>) {
    // FIRST ACTION OF THE PROCESS — no logger, no class init that prints, nothing.
    //
    // The MCP-stdio convention reserves stdout for NDJSON JSON-RPC frames. Once the
    // process commits to that mode it cannot un-commit, and any earlier println from
    // a logger fallback, kotlinx-coroutines warning, or posthog noise would corrupt
    // the very first frame. So we branch on `--mcp` BEFORE anything else: the MCP
    // path captures stdin/stdout and redirects the global System.out → stderr; every
    // other path (help / version / unknown) runs like a normal CLI tool with stdout
    // intact.
    //
    // `parseCliMode` is intentionally pure and does not touch System.out.
    val mode = parseCliMode(args)
    val homePaths = try {
        resolveHomePaths(parseHomeOverride(args))
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(64)
    }
    homePaths.mkdirsAll()
    System.setProperty("proxy.log.dir", homePaths.logsDir.toString())
    System.setProperty("proxy.log.session", LocalDateTime.now().format(LOG_SESSION_FORMAT))
    // `--debug` is orthogonal to the mode and must be honoured even in MCP
    // mode. Apply BEFORE any class load that might trigger logback init —
    // logback reads the `proxy.log.level` system property on first use and
    // pins the level for the JVM lifetime.
    applyDebugLogging(parseDebugFlag(args))

    val proxyVersion = loadProxyVersion()
    if (mode !is CliMode.Mcp) {
        NpxBeacon(proxyVersion = proxyVersion).startInteractive(beaconInvocation(mode))
        NpxUpdateChecker(currentVersion = proxyVersion).startOneShot()
        exitProcess(runCli(mode, homePaths))
    }

    val mcpStdin: InputStream = System.`in`
    val mcpStdout: PrintStream = System.out

    System.setOut(System.err)
    val beacon = NpxBeacon(proxyVersion = proxyVersion)
    val updateChecker = NpxUpdateChecker(currentVersion = proxyVersion)
    beacon.startMcp()
    updateChecker.startPeriodic()

    val ignoredMcpTokens = mcpIgnoredTokens(args)
    if (ignoredMcpTokens.isNotEmpty()) {
        org.slf4j.LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.proxy.Main")
            .debug("--mcp selected; ignored CLI argument(s): {}", ignoredMcpTokens.joinToString(" "))
    }

    try {
        MainContext(
            args = args.toList(),
            mcpStdin = mcpStdin,
            mcpStdout = mcpStdout,
            homePaths = homePaths,
        ).mainImpl()
    } catch (t: Throwable) {
        System.err.println("Unexpected error ${t.message}")
        t.printStackTrace(System.err)
        exitProcess(64)
    } finally {
        updateChecker.close()
        beacon.close()
    }
}

internal fun MainContext.mainImpl() {
    // Construct every dependent service explicitly here — the npx-kt module
    // does not use any DI framework, so main.kt is the wiring root.
    val proxyVersion = loadProxyVersion()
    val legacyHomeDir = File(System.getProperty("user.home"))
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

    val discovery = IdeDiscoveryService(
        markersDir = homePaths.markersDir.toFile(),
        legacyHomeDir = legacyHomeDir,
        allowHosts = allowHosts,
    )
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
    // com.jonnyzzz.mcpSteroid.proxy.attic.legacyProxyMain and is intentionally
    // unreachable; the source is retained in the attic package.
    //
    // Alongside the stdio server, the new IDE monitor runs:
    //   discovery → reads <pid>.mcp-steroid JSON markers from $MCP_STEROID_HOME/markers
    //               plus legacy .<pid>.mcp-steroid markers from $HOME during the transition
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
