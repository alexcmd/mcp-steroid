/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class IdeDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `downloadFile writes destination and removes temp file`() {
        val payload = "downloaded archive".toByteArray()
        val dest = File(tmp.root, "idea.tar.gz")
        val tempFile = File(tmp.root, "idea.tar.gz.tmp")

        withArchiveServer(payload) { url ->
            downloadFile(url, dest)
        }

        assertTrue("destination archive must exist after download", dest.isFile)
        assertFalse("temporary archive must be removed after successful move", tempFile.exists())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `downloadFile replaces existing destination bytes`() {
        val stale = "stale archive".toByteArray()
        val payload = "fresh archive".toByteArray()
        val dest = File(tmp.root, "idea.tar.gz")
        dest.writeBytes(stale)
        val tempFile = File(tmp.root, "idea.tar.gz.tmp")

        withArchiveServer(payload) { url ->
            downloadFile(url, dest)
        }

        assertTrue("destination archive must still exist after replacement", dest.isFile)
        assertFalse("temporary archive must be removed after replacement", tempFile.exists())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `resolveAndDownload verifies checksum URL and deletes archive on mismatch`() {
        val payload = "verified archive".toByteArray()
        val goodSha256 = sha256(payload)
        var checksumText = "$goodSha256  archive.tar.gz\n"

        withServer({ server ->
            server.createContext("/archive.tar.gz") { exchange -> sendBytes(exchange, payload) }
            server.createContext("/archive.tar.gz.sha256") { exchange -> sendText(exchange, checksumText) }
        }) { baseUrl ->
            val distribution = IdeDistribution.FromUrl(
                product = IdeProduct.IntelliJIdeaCommunity,
                url = "$baseUrl/archive.tar.gz",
                checksumUrl = "$baseUrl/archive.tar.gz.sha256",
            )

            val archive = distribution.resolveAndDownload(tmp.root, os = HostOs.LINUX)

            assertArrayEquals(payload, archive.readBytes())

            checksumText = "${"0".repeat(64)}  archive.tar.gz\n"
            val error = expectError { distribution.resolveAndDownload(tmp.root, os = HostOs.LINUX) }

            assertTrue("expected checksum mismatch, got: ${error.message}", error.message!!.contains("SHA-256 mismatch"))
            assertFalse("corrupt cached/downloaded archive must be deleted", archive.exists())
        }
    }

    @Test
    fun `resolveAndDownload without checksum logs warning but succeeds`() {
        val payload = "unverified archive".toByteArray()
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeDownloader") as Logger

        withCapturedLogger(logger) { appender ->
            logger.level = Level.WARN
            withArchiveServer(payload) { url ->
                val archive = IdeDistribution.FromUrl(
                    product = IdeProduct.IntelliJIdeaCommunity,
                    url = url,
                ).resolveAndDownload(tmp.root, os = HostOs.LINUX)

                assertArrayEquals(payload, archive.readBytes())
            }

            assertTrue(
                "expected WARN about missing SHA-256",
                appender.list.any {
                    it.level == Level.WARN &&
                        it.formattedMessage.contains("No SHA-256 checksum available")
                },
            )
        }
    }

    @Test
    fun `downloadFile resumes interrupted temp file with Range request`() {
        val payload = payloadBytes(size = 5 * 1024 * 1024)
        val interruptAfterBytes = 1_234_567
        val dest = File(tmp.root, "idea.tar.gz")
        val tempFile = File(tmp.root, "idea.tar.gz.tmp")
        val requestRanges = CopyOnWriteArrayList<String?>()
        val requests = AtomicInteger()

        withServer({ server ->
            server.createContext("/archive.tar.gz") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range")
                requestRanges.add(range)
                if (requests.incrementAndGet() == 1) {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { output ->
                        output.write(payload, 0, interruptAfterBytes)
                        output.flush()
                    }
                } else {
                    val offset = parseRangeOffset(range)
                    exchange.responseHeaders.add("Content-Range", "bytes $offset-${payload.size - 1}/${payload.size}")
                    exchange.sendResponseHeaders(206, (payload.size - offset).toLong())
                    exchange.responseBody.use { output -> output.write(payload, offset, payload.size - offset) }
                }
            }
        }) { baseUrl ->
            val url = "$baseUrl/archive.tar.gz"
            expectError { downloadFile(url, dest) }

            assertTrue("interrupted download should leave a temp file", tempFile.isFile)
            assertEquals(interruptAfterBytes.toLong(), tempFile.length())

            downloadFile(url, dest)
        }

        assertFalse("temp file must be moved away after successful resume", tempFile.exists())
        assertArrayEquals(payload, dest.readBytes())
        assertEquals(listOf(null, "bytes=$interruptAfterBytes-"), requestRanges.toList())
    }

    @Test
    fun `downloadFile restarts when server ignores Range request`() {
        val payload = payloadBytes(size = 5 * 1024 * 1024)
        val resumeBytes = 777_777
        val dest = File(tmp.root, "idea.tar.gz")
        val tempFile = File(tmp.root, "idea.tar.gz.tmp")
        tempFile.writeBytes(payload.copyOfRange(0, resumeBytes))
        val requestRanges = CopyOnWriteArrayList<String?>()

        withServer({ server ->
            server.createContext("/archive.tar.gz") { exchange ->
                requestRanges.add(exchange.requestHeaders.getFirst("Range"))
                sendBytes(exchange, payload)
            }
        }) { baseUrl ->
            downloadFile("$baseUrl/archive.tar.gz", dest)
        }

        assertFalse("temp file must be moved away after fresh restart", tempFile.exists())
        assertArrayEquals(payload, dest.readBytes())
        assertEquals(listOf("bytes=$resumeBytes-"), requestRanges.toList())
    }

    private fun withArchiveServer(payload: ByteArray, block: (String) -> Unit) {
        withServer({ server ->
            server.createContext("/archive.tar.gz") { exchange -> sendBytes(exchange, payload) }
        }) { baseUrl ->
            block("$baseUrl/archive.tar.gz")
        }
    }

    private fun withServer(configure: (HttpServer) -> Unit, block: (String) -> Unit) {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        configure(server)
        server.start()
        try {
            val baseUrl = URI("http://127.0.0.1:${server.address.port}").toString()
            block(baseUrl)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun sendBytes(exchange: HttpExchange, payload: ByteArray) {
        exchange.sendResponseHeaders(200, payload.size.toLong())
        exchange.responseBody.use { output -> output.write(payload) }
    }

    private fun sendText(exchange: HttpExchange, text: String) {
        val bytes = text.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    private fun parseRangeOffset(range: String?): Int {
        require(range != null) { "Range header is required" }
        require(range.startsWith("bytes=")) { "Unexpected Range header: $range" }
        return range.removePrefix("bytes=").substringBefore('-').toInt()
    }

    private fun payloadBytes(size: Int): ByteArray = ByteArray(size) { index -> (index * 31).toByte() }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private inline fun expectError(block: () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        fail("Expected an exception; none thrown")
        throw AssertionError("unreachable")
    }

    private fun withCapturedLogger(
        logger: Logger,
        block: (ListAppender<ILoggingEvent>) -> Unit,
    ) {
        val originalLevel = logger.level
        val originalAdditive = logger.isAdditive
        val appender = ListAppender<ILoggingEvent>()
        appender.context = logger.loggerContext
        appender.start()
        logger.addAppender(appender)
        logger.isAdditive = false
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = originalLevel
            logger.isAdditive = originalAdditive
        }
    }
}
