/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.logger
import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeDiscoveryService
import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeMonitorService
import com.jonnyzzz.mcpSteroid.proxy.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.proxy.server.runStubStdioMcpServer
import com.jonnyzzz.mcpSteroid.server.NpxStreamClientInfo
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.PrintStream
import java.util.UUID
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    val args = NpxKtArgs(args)

    val homePaths = resolveHomePathsOrDie(args)

    //setup logging. That is essential to avoid logger usages BEFORE this statement
    configureLoggingAndLogStarted(homePaths, args)

    val lifetime = CloseableStackHost()
    try {
        NpxKtServices(homePaths, args, lifetime).mainImpl1()
    } catch (t: Throwable) {
        System.err.println("Unexpected error ${t.message}")
        t.printStackTrace(System.err)
        exitProcess(64)
    } finally {
        lifetime.closeAllStacks()
    }
}

fun NpxKtServices.mainImpl1() {
    class DevrigCoroutineExceptionHandler

    val log = logger<DevrigCoroutineExceptionHandler>()
    val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        log.warn("devrig coroutine exception: ${throwable.message} in $context", throwable)
    }

    runBlocking(Dispatchers.IO + CoroutineName("devrig") + exceptionHandler + SupervisorJob()) {
        coroutineScope {
            mainImpl2()
        }
    }
}

suspend fun NpxKtServices.mainImpl2() = coroutineScope{
    launch {
        delay(Random.nextInt(200, 1300).milliseconds)
        checkForUpdates()
    }

    val mode = args.parseCliMode()

    launch {
        beacon.captureStarted(mode)
    }

    if (mode is CliMode.Mcp) {
        beacon.runHeartbeat()
    }


    if (mode !is CliMode.Mcp) {
        try {
            val cliResult = runCli(mode, homePaths)
            exitProcess(cliResult)
        } catch (t: Throwable) {
            System.err.println("Unexpected error calling $mode. ${t.message}")
            t.printStackTrace(System.err)
            exitProcess(64)
        }
    }

    val mcpStdin: InputStream = System.`in`
    val mcpStdout: PrintStream = System.out
    System.setOut(System.err)

    try {
        mainImplMcp(mcpStdin, mcpStdout)
    } catch (t: Throwable) {
        System.err.println("Unexpected error ${t.message}")
        t.printStackTrace(System.err)
        exitProcess(64)
    }
}

suspend fun NpxKtServices.mainImplMcp(mcpStdin: InputStream, mcpStdout: PrintStream) = coroutineScope {
    // Construct every dependent service explicitly here — the npx-kt module
    // does not use any DI framework, so main.kt is the wiring root.
    val allowHosts = listOf("localhost", "127.0.0.1", "host.docker.internal")
    val clientInfo = NpxStreamClientInfo(
        client = "devrig",
        clientPid = ProcessHandle.current().pid(),
        clientVersion = ProxyVersionMetadata.getProxyVersion(),
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
