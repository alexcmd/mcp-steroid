/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors

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

    private fun withArchiveServer(payload: ByteArray, block: (String) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/archive.tar.gz") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { output -> output.write(payload) }
        }
        server.start()
        try {
            val url = URI("http://127.0.0.1:${server.address.port}/archive.tar.gz").toString()
            block(url)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
