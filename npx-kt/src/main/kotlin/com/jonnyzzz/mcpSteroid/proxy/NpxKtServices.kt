package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeDiscoveryService
import com.jonnyzzz.mcpSteroid.proxy.monitor.IdeMonitorService
import com.jonnyzzz.mcpSteroid.proxy.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.server.NpxStreamClientInfo
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.util.UUID
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NpxKtServices(
    private val lifetime: CloseableStack,

    val homePaths: HomePaths,
    val mcpStdin: InputStream,
    val mcpStdout: PrintStream,
) {
    fun lifetime(name: String): CloseableStack = lifetime.nestedStack(name)

    private val backgroundJob = SupervisorJob().also { job ->
        lifetime.registerCleanupAction { job.cancel() }
    }

    val backgroundScope: CoroutineScope =
        CoroutineScope(CoroutineName("devrig-background") + Dispatchers.IO + backgroundJob)

    val allowHosts: List<String> = listOf("localhost", "127.0.0.1", "host.docker.internal")

    val backendManager: BackendManager by lazy {
        BackendManager(homePaths)
    }

    val backendProvisioner: BackendProvisioner by lazy {
        BackendProvisioner()
    }

    val commandHttpClient: HttpClient by lazy {
        createCommandHttpClient().also { httpClient ->
            lifetime.registerCleanupAction { httpClient.close() }
        }
    }

    val mcpHttpClient: HttpClient by lazy {
        createMcpHttpClient().also { httpClient ->
            lifetime.registerCleanupAction { httpClient.close() }
        }
    }

    val ideDiscovery: IdeDiscoveryService by lazy {
        createIdeDiscoveryService()
    }

    val ideMonitor: IdeMonitorService by lazy {
        createIdeMonitorService(mcpHttpClient, ideDiscovery)
    }

    val portDiscovery: IntelliJPortDiscovery by lazy {
        createPortDiscovery(commandHttpClient).also { discovery ->
            lifetime.registerCleanupAction { discovery.close() }
        }
    }

    val clientInfo: NpxStreamClientInfo by lazy {
        NpxStreamClientInfo(
            client = "devrig",
            clientPid = ProcessHandle.current().pid(),
            clientVersion = ProxyVersionMetadata.getProxyVersion(),
            clientInstanceId = "npx-kt-${UUID.randomUUID()}",
            platform = System.getProperty("os.name"),
            arch = System.getProperty("os.arch"),
        )
    }

    val backendClientInfo: NpxStreamClientInfo by lazy {
        clientInfo.copy(
            client = "devrig (backend)",
            clientInstanceId = "backend-${UUID.randomUUID()}",
        )
    }

    val beacon by lazy {
        NpxBeacon(homePaths, lifetime)
    }

    fun createCommandHttpClient(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    fun createMcpHttpClient(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    fun createIdeDiscoveryService(): IdeDiscoveryService =
        IdeDiscoveryService(
            markersDir = homePaths.markersDir.toFile(),
            legacyHomeDir = File(System.getProperty("user.home")),
            allowHosts = allowHosts,
        )

    fun createIdeMonitorService(
        httpClient: HttpClient,
        discovery: IdeDiscoveryService,
    ): IdeMonitorService =
        IdeMonitorService(
            httpClient = httpClient,
            discovery = discovery,
            clientInfo = clientInfo,
        )

    fun createPortDiscovery(httpClient: HttpClient): IntelliJPortDiscovery =
        IntelliJPortDiscovery(httpClient = httpClient)
}
