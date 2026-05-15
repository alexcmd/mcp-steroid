/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class BackendCommandDownloadListTest {

    @Test
    fun `text lists downloadable IDEs with aligned columns paid annotation and lookup errors`() = runBlocking {
        val rows = collectAvailableBackendDownloads(
            versionResolver = FakeVersionResolver(
                versions = mapOf(
                    "idea-community" to Result.success(AvailableBackendRelease("2025.3.3", "2025-12-08")),
                    "pycharm-community" to Result.success(AvailableBackendRelease("2025.3.3", "2025-12-08")),
                    "android-studio" to Result.failure(IllegalStateException("offline products API")),
                    "goland" to Result.success(AvailableBackendRelease("2025.3.0", "2025-12-09")),
                    "webstorm" to Result.success(AvailableBackendRelease("2025.3.0", "2025-12-09")),
                    "rider" to Result.success(AvailableBackendRelease("2025.3.0", "2025-12-09")),
                    "clion" to Result.success(AvailableBackendRelease("2025.3.0", "2025-12-09")),
                ),
            ),
        )
        val text = renderText(rows)

        assertTrue(text.startsWith("devrig v"), text)
        assertTrue(text.contains("Available IDEs (defaults to latest stable):"), text)
        assertTrue(text.contains("(paid — requires --allow-paid)"), text)
        assertTrue(text.contains("free-for-non-commercial"), text)
        assertTrue(text.contains("(version lookup failed: offline products API)"), text)
        assertTrue(text.contains("2025-12-08"), text)
        assertTrue(text.contains("Run:  devrig backend download <id> [--version <v>] [--allow-paid]"), text)

        val lines = productLines(text)
        val ideaCommunity = lines.single { it.contains("idea-community") }
        val pyCharmCommunity = lines.single { it.contains("pycharm-community") }
        val androidStudio = lines.single { it.contains("android-studio") }
        val goLand = lines.single { it.contains("goland") }
        val ideaUltimate = lines.single { it.contains("idea-ultimate") && it.contains("IntelliJ IDEA Ultimate") }
        val pyCharmPro = lines.single { it.contains("pycharm-pro") && it.contains("PyCharm Professional") }

        assertTrue(lines.indexOf(ideaCommunity) < lines.indexOf(goLand), text)
        assertTrue(lines.indexOf(pyCharmCommunity) < lines.indexOf(goLand), text)
        assertTrue(lines.indexOf(androidStudio) < lines.indexOf(goLand), text)
        assertTrue(lines.indexOf(goLand) < lines.indexOf(ideaUltimate), text)
        assertTrue(lines.indexOf(ideaUltimate) < lines.indexOf(pyCharmPro), text)

        val versionColumns = mapOf(
            ideaCommunity to "2025.3.3",
            pyCharmCommunity to "2025.3.3",
            androidStudio to "(version lookup failed: offline products API)",
            goLand to "2025.3.0",
        ).map { (line, version) -> line.indexOf(version) }.toSet()
        assertEquals(1, versionColumns.size, "version column must align in:\n$text")

        val dateColumns = mapOf(
            ideaCommunity to "2025-12-08",
            pyCharmCommunity to "2025-12-08",
            goLand to "2025-12-09",
        ).map { (line, releaseDate) -> line.indexOf(releaseDate) }.toSet()
        assertEquals(1, dateColumns.size, "release date column must align in:\n$text")
    }

    @Test
    fun `json exposes available schema with null versions for paid and failed lookups`() = runBlocking {
        val rows = collectAvailableBackendDownloads(
            versionResolver = FakeVersionResolver(
                versions = mapOf(
                    "android-studio" to Result.failure(IllegalStateException("network down")),
                ),
            ),
        )
        val root = renderJson(rows)

        assertEquals(setOf("tool", "available"), root.keys)
        assertEquals("devrig", root["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val available = root["available"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(
                "idea-community",
                "pycharm-community",
                "android-studio",
                "goland",
                "webstorm",
                "rider",
                "clion",
                "idea-ultimate",
                "pycharm-pro",
            ),
            available.map { it["id"]!!.jsonPrimitive.content },
        )

        val ideaCommunity = available.single { it["id"]!!.jsonPrimitive.content == "idea-community" }
        assertEquals("IIC", ideaCommunity["code"]!!.jsonPrimitive.content)
        assertEquals("IntelliJ IDEA Community", ideaCommunity["displayName"]!!.jsonPrimitive.content)
        assertEquals("free", ideaCommunity["licenseTier"]!!.jsonPrimitive.content)
        assertEquals("2025.3.test", ideaCommunity["version"]!!.jsonPrimitive.content)
        assertEquals("2025-12-08", ideaCommunity["releaseDate"]!!.jsonPrimitive.content)
        assertFalse(ideaCommunity["requiresAllowPaid"]!!.jsonPrimitive.boolean)

        val rider = available.single { it["id"]!!.jsonPrimitive.content == "rider" }
        assertEquals("free-for-non-commercial", rider["licenseTier"]!!.jsonPrimitive.content)
        assertEquals("2025.3.test", rider["version"]!!.jsonPrimitive.content)

        val android = available.single { it["id"]!!.jsonPrimitive.content == "android-studio" }
        assertNull(android["version"]!!.jsonPrimitive.contentOrNull)
        assertEquals("network down", android["versionLookupError"]!!.jsonPrimitive.content)
        assertFalse(android["requiresAllowPaid"]!!.jsonPrimitive.boolean)

        val paid = available.single { it["id"]!!.jsonPrimitive.content == "idea-ultimate" }
        assertEquals("paid", paid["licenseTier"]!!.jsonPrimitive.content)
        assertNull(paid["version"]!!.jsonPrimitive.contentOrNull)
        assertFalse("releaseDate" in paid)
        assertTrue(paid["requiresAllowPaid"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `paid products are not resolved`() = runBlocking {
        val resolver = CountingResolver()

        collectAvailableBackendDownloads(versionResolver = resolver)

        assertEquals(
            setOf("idea-community", "pycharm-community", "android-studio", "goland", "webstorm", "rider", "clion"),
            resolver.calls.toSet(),
        )
        assertTrue("idea-ultimate" !in resolver.calls, "paid IntelliJ IDEA Ultimate must not hit the release resolver")
        assertTrue("pycharm-pro" !in resolver.calls, "paid PyCharm Professional must not hit the release resolver")
    }

    @Test
    fun `text command flushes banner before resolver work`() = runBlocking {
        val buf = ByteArrayOutputStream()
        val out = PrintStream(buf, true, Charsets.UTF_8)
        val resolverEntered = CompletableDeferred<String>()
        val releaseResolver = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Default) {
            runBackendDownloadListCommand(
                out = out,
                json = false,
                availableDownloads = {
                    resolverEntered.complete(buf.toString(Charsets.UTF_8))
                    releaseResolver.await()
                    emptyList()
                },
            )
        }

        val stdoutAtResolverStart = withTimeout(5_000) {
            resolverEntered.await()
        }
        assertTrue(stdoutAtResolverStart.startsWith("devrig v"), stdoutAtResolverStart)
        assertFalse(stdoutAtResolverStart.contains("Available IDEs"), stdoutAtResolverStart)

        releaseResolver.complete(Unit)
        job.join()
    }

    private fun renderText(rows: List<AvailableBackendDownload>): String {
        val buf = ByteArrayOutputStream()
        renderBackendDownloadListText(rows, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8)
    }

    private fun renderJson(rows: List<AvailableBackendDownload>) = Json
        .parseToJsonElement(
            ByteArrayOutputStream().also { buf ->
                renderBackendDownloadListJson(rows, PrintStream(buf, true, Charsets.UTF_8))
            }.toString(Charsets.UTF_8),
        ).jsonObject

    private fun productLines(text: String): List<String> = text.lines()
        .filter { it.trimStart().startsWith("[") }

    private class FakeVersionResolver(
        private val versions: Map<String, Result<AvailableBackendRelease>>,
    ) : AvailableBackendVersionResolver {
        override suspend fun resolveLatestStableRelease(product: IdeProduct): AvailableBackendRelease =
            versions[product.id]?.getOrThrow() ?: AvailableBackendRelease("2025.3.test", "2025-12-08")
    }

    private class CountingResolver : AvailableBackendVersionResolver {
        val calls = mutableListOf<String>()

        override suspend fun resolveLatestStableRelease(product: IdeProduct): AvailableBackendRelease {
            calls += product.id
            return AvailableBackendRelease("2025.3.test", "2025-12-08")
        }
    }
}
