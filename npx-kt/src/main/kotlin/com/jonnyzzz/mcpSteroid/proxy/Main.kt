/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.logger
import com.jonnyzzz.mcpSteroid.proxy.server.runStubStdioMcpServer
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main(rawArgs: Array<String>) {
    // Replace stdout immediately. MCP stdio reserves the original stdout for
    // frames, and command detection / service setup must not leak there.
    val mcpStdin = System.`in`
    val mcpStdout = System.out
    System.setOut(System.err)

    val command = parseNpxKtCommand(rawArgs)
    val headliner = buildHeadliner()
    if (command is NpxKtCommand.MCP) {
        System.err.println(headliner)
    } else {
        System.setOut(mcpStdout)
    }

    val homePaths = resolveHomePathsOrDie()

    //setup logging. That is essential to avoid logger usages BEFORE this statement
    configureLoggingAndLogStarted(homePaths, rawArgs.toList(), command.debug)

    val lifetime = CloseableStackHost()
    val exitCode = try {
        NpxKtServices(
            lifetime = lifetime,
            homePaths = homePaths,
            mcpStdin = mcpStdin,
            mcpStdout = mcpStdout,
        ).mainImpl1(command, headliner)
    } catch (t: Throwable) {
        System.err.println("Unexpected error ${t.message}")
        t.printStackTrace(System.err)
        64
    } finally {
        lifetime.closeAllStacks()
    }
    exitProcess(exitCode)
}

private fun buildHeadliner(): String = buildString {
    val proxyVersion = ProxyVersionMetadata.getProxyVersion()
    appendLine("devrig v$proxyVersion — This environment empowers your AI with the best deterministic coding tools.")
    appendLine()
}

fun NpxKtServices.mainImpl1(
    command: NpxKtCommand,
    headliner: String,
): Int {
    class DevrigCoroutineExceptionHandler

    val log = logger<DevrigCoroutineExceptionHandler>()
    val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        log.warn("devrig coroutine exception: ${throwable.message} in $context", throwable)
    }

    return runBlocking(Dispatchers.IO + CoroutineName("devrig") + exceptionHandler + SupervisorJob()) {
        coroutineScope {
            mainImpl2(command, headliner)
        }
    }
}

suspend fun NpxKtServices.mainImpl2(
    command: NpxKtCommand,
    headliner: String,
): Int = coroutineScope {
    backgroundScope.launch {
        delay(Random.nextInt(200, 1300).milliseconds)
        checkForUpdates()
    }

    backgroundScope.launch {
        beacon.captureStarted(command)
    }

    if (command is NpxKtCommand.MCP) {
        beacon.runHeartbeat()
        try {
            mainImplMcp()
            return@coroutineScope 0
        } catch (t: Throwable) {
            System.err.println("Unexpected error ${t.message}")
            t.printStackTrace(System.err)
            return@coroutineScope 64
        }
    }

    mcpStdout.println(headliner)
    try {
        runCli(command)
    } catch (t: Throwable) {
        System.err.println("Unexpected error calling $command. ${t.message}")
        t.printStackTrace(System.err)
        64
    }
}

suspend fun NpxKtServices.mainImplMcp() = coroutineScope {
    // npx-kt boots a real MCP stdio server backed by McpStdioServer +
    // McpSteroidTools (with stub handlers — see StubMcpSteroidTools). The legacy
    // proxy path that aggregates discovered IDE MCP servers lives in
    // com.jonnyzzz.mcpSteroid.proxy.attic.legacyProxyMain and is intentionally
    // unreachable; the source is retained in the attic package.
    //
    // Alongside the stdio server, the new IDE monitor runs:
    //   discovery → reads <pid>.mcp-steroid JSON markers from the devrig home markers directory
    //               plus legacy .<pid>.mcp-steroid markers from $HOME during the transition
    //   monitor   → opens one POST /npx/v1/projects/stream per IDE,
    //               receives push notifications on project open/close

    val discoveryJob = ideDiscovery.start(this)
    val monitorJob = ideMonitor.start(this)
    val portDiscoveryJob = portDiscovery.start(this)
    try {
        runStubStdioMcpServer(this@mainImplMcp)
    } finally {
        portDiscoveryJob.cancel()
        monitorJob.cancel()
        discoveryJob.cancel()
    }
}
