/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.proxy.monitor.AboutResponse
import com.jonnyzzz.mcpSteroid.proxy.monitor.DiscoveredIdeByPort
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class BackendProvisionTest {
    private val parser = Json { ignoreUnknownKeys = true }

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val clients = mutableListOf<HttpClient>()

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.stop(0L, 0L) }
    }

    @Test
    fun `provision parser target ids are stable`() {
        assertEquals("port-63342", provisionTargetId(63342))
        assertEquals("devrig backend provision port-63342", provisionCommand("port-63342"))
    }

    @Test
    fun `mac IDEA Ultimate api-about maps to IntelliJIdea selector plugins folder`(@TempDir tempDir: Path) {
        val about = AboutResponse(
            name = "IntelliJ IDEA 2026.1.1",
            productName = "IDEA",
            baselineVersion = 261,
            buildNumber = "261.23567.138",
        )

        val selector = deriveIdePathSelector(about, productCode = "IU")
        val path = defaultIdePluginsDir(
            selector = selector.selector,
            vendor = selector.vendor,
            os = HostOs.MAC,
            userHome = tempDir,
            env = emptyMap(),
        )

        assertEquals("IntelliJIdea2026.1", selector.selector)
        assertEquals("JetBrains", selector.vendor)
        assertEquals(tempDir.resolve("Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins"), path)
    }

    @Test
    fun `windows PyCharm Community maps through product code to APPDATA plugins folder`() {
        val about = AboutResponse(
            name = "PyCharm Community Edition 2025.3",
            productName = "PyCharm",
            baselineVersion = 253,
            buildNumber = "253.1",
        )

        val selector = deriveIdePathSelector(about, productCode = "PC")
        val path = defaultIdePluginsDir(
            selector = selector.selector,
            vendor = selector.vendor,
            os = HostOs.WINDOWS,
            userHome = Path.of("C:\\Users\\ada"),
            env = mapOf("APPDATA" to "C:\\Users\\ada\\AppData\\Roaming"),
        )

        assertEquals("PyCharmCE2025.3", selector.selector)
        assertEquals("C:\\Users\\ada\\AppData\\Roaming\\JetBrains\\PyCharmCE2025.3\\plugins", path.toString())
    }

    @Test
    fun `linux GoLand maps to XDG data plugin directory without an extra plugins segment`() {
        val about = AboutResponse(
            name = "GoLand 2026.1",
            productName = "GoLand",
            baselineVersion = 261,
            buildNumber = "GO-261.10",
        )

        val selector = deriveIdePathSelector(about)
        val path = defaultIdePluginsDir(
            selector = selector.selector,
            vendor = selector.vendor,
            os = HostOs.LINUX,
            userHome = Path.of("/home/ada"),
            env = mapOf("XDG_DATA_HOME" to "/xdg/data"),
        )

        assertEquals("GoLand2026.1", selector.selector)
        assertEquals(Path.of("/xdg/data/JetBrains/GoLand2026.1"), path)
    }

    @Test
    fun `provision list text prints action command next to each port-discovered IDE`() {
        val text = renderProvisionText(
            listOf(
                ProvisionTarget(
                    id = "port-63342",
                    ide = portIde(port = 63342, productFullName = "IntelliJ IDEA 2026.1.1", buildNumber = "261.23567.138"),
                ),
            )
        )

        assertTrue(text.startsWith("devrig v"), text)
        assertTrue(text.contains("Port-discovered IDEs that can be provisioned:"), text)
        assertTrue(text.contains("port-63342"), text)
        assertTrue(text.contains("run: devrig backend provision port-63342"), text)
    }

    @Test
    fun `provision list json exposes actions array`() {
        val root = renderProvisionJson(
            listOf(
                ProvisionTarget(
                    id = "port-63342",
                    ide = portIde(port = 63342),
                ),
            )
        )
        assertEquals(setOf("tool", "targets"), root.keys)
        val target = root["targets"]!!.jsonArray.single().jsonObject
        assertEquals("port-63342", target["id"]!!.jsonPrimitive.content)
        val action = target["actions"]!!.jsonArray.single().jsonObject
        assertEquals("provision", action["id"]!!.jsonPrimitive.content)
        assertEquals("devrig backend provision port-63342", action["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `provision copies bundled plugin once and second run is idempotent`(@TempDir tempDir: Path) = runBlocking {
        val sourcePlugin = tempDir.resolve("source-plugin")
        sourcePlugin.resolve("lib").createDirectories()
        sourcePlugin.resolve("lib/plugin.txt").writeText("first")
        sourcePlugin.resolve("META-INF").createDirectories()
        sourcePlugin.resolve("META-INF/plugin.xml").writeText("<idea-plugin/>")

        val port = ServerSocket(0).use { it.localPort }
        val installPluginOrigins = mutableListOf<String?>()
        val server = ideServer(
            port = port,
            aboutBody = """
                {
                  "name": "IntelliJ IDEA 2026.1.1",
                  "productName": "IDEA",
                  "edition": null,
                  "baselineVersion": 261,
                  "buildNumber": "261.23567.138"
                }
            """.trimIndent(),
            installPluginBody = """{"name":"IntelliJ IDEA 2026.1.1","buildNumber":"IU-261.23567.138"}""",
            installPluginOrigins = installPluginOrigins,
        )
        servers += server
        val httpClient = httpClient()
        clients += httpClient

        val provisioner = BackendProvisioner(
            bundledPluginResolver = FixedBundledPluginResolver(sourcePlugin),
            os = HostOs.MAC,
            userHome = tempDir.resolve("home"),
            env = emptyMap(),
            portRanges = listOf(port..port),
        )

        val first = provisioner.provision("port-$port", httpClient)
        assertFalse(first.alreadyProvisioned)
        assertEquals("IntelliJIdea2026.1", first.selector)
        assertEquals("IU", first.productCode)
        assertEquals(tempDir.resolve("home/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/mcp-steroid"), first.pluginPath)
        assertEquals("first", first.pluginPath.resolve("lib/plugin.txt").readText())
        assertTrue("http://localhost" in installPluginOrigins)

        sourcePlugin.resolve("lib/plugin.txt").writeText("second")
        val second = provisioner.provision("port-$port", httpClient)
        assertTrue(second.alreadyProvisioned)
        assertEquals(first.pluginPath, second.pluginPath)
        assertEquals("first", second.pluginPath.resolve("lib/plugin.txt").readText())
    }

    @Test
    fun `provision json reports installed status and restart requirement`(@TempDir tempDir: Path) {
        val result = ProvisionResult(
            id = "port-63342",
            ide = portIde(port = 63342),
            about = AboutResponse(
                name = "IntelliJ IDEA 2026.1.1",
                productName = "IDEA",
                baselineVersion = 261,
                buildNumber = "261.23567.138",
            ),
            productCode = "IU",
            selector = "IntelliJIdea2026.1",
            pluginsDir = tempDir.resolve("plugins"),
            pluginPath = tempDir.resolve("plugins/mcp-steroid"),
            alreadyProvisioned = false,
        )

        val buf = ByteArrayOutputStream()
        val exit = runBackendProvisionCommand(
            out = PrintStream(buf, true, Charsets.UTF_8),
            homePaths = HomePaths(tempDir),
            mode = CliMode.Backend.Provision("port-63342", json = true),
            provision = { result },
        )
        val root = parser.parseToJsonElement(buf.toString(Charsets.UTF_8)).jsonObject

        assertEquals(0, exit)
        assertEquals("provision", root["action"]!!.jsonPrimitive.content)
        assertEquals("port-63342", root["id"]!!.jsonPrimitive.content)
        assertEquals("installed", root["status"]!!.jsonPrimitive.content)
        assertEquals(false, root["alreadyProvisioned"]!!.jsonPrimitive.boolean)
        assertEquals(true, root["restartRequired"]!!.jsonPrimitive.boolean)
        assertEquals(result.pluginPath.toString(), root["pluginPath"]!!.jsonPrimitive.content)
        assertEquals(result.pluginsDir.toString(), root["pluginsPath"]!!.jsonPrimitive.content)
        assertEquals("IU", root["productCode"]!!.jsonPrimitive.contentOrNull)
    }

    private fun renderProvisionText(rows: List<ProvisionTarget>): String {
        val buf = ByteArrayOutputStream()
        renderBackendProvisionListText(rows, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8)
    }

    private fun renderProvisionJson(rows: List<ProvisionTarget>) = parser
        .parseToJsonElement(
            ByteArrayOutputStream().also { buf ->
                renderBackendProvisionListJson(rows, PrintStream(buf, true, Charsets.UTF_8))
            }.toString(Charsets.UTF_8),
        ).jsonObject

    private fun portIde(
        port: Int,
        productFullName: String? = "IntelliJ IDEA 2026.1.1",
        productName: String? = "IDEA",
        buildNumber: String? = "261.23567.138",
        baselineVersion: Int? = 261,
        edition: String? = null,
    ) = DiscoveredIdeByPort(
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = productName,
        productFullName = productFullName,
        edition = edition,
        baselineVersion = baselineVersion,
        buildNumber = buildNumber,
    )

    private fun httpClient(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 2_000
            requestTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
        }
        expectSuccess = false
    }

    private fun ideServer(
        port: Int,
        aboutBody: String,
        installPluginBody: String,
        installPluginOrigins: MutableList<String?>,
    ): EmbeddedServer<*, *> {
        val server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                get("/api/about") {
                    call.respondText(aboutBody, ContentType.Application.Json)
                }
                get("/api/installPlugin") {
                    installPluginOrigins += call.request.header("Origin")
                    call.respondText(installPluginBody, ContentType.Application.Json)
                }
            }
        }.also { it.start(wait = false) }
        runBlocking { server.monitor.subscribe(ApplicationStarted) {} }
        return server
    }

    private class FixedBundledPluginResolver(private val dir: Path) : BundledPluginResolver {
        override fun resolveBundledPluginDir(): Path = dir
    }
}
