/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.websitegen

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheTest {
    @Serializable
    data class Meta(val name: String, val size: Long)

    // ── in-memory primitive ──────────────────────────────────────────────────────────────────────

    @Test
    fun `in-memory get returns null until put, then the stored bytes`() {
        val cache = Cache.inMemory()
        assertNull(cache.get("k"))
        cache.put("k", "hello".encodeToByteArray())
        assertContentEquals("hello".encodeToByteArray(), cache.get("k"))
    }

    @Test
    fun `in-memory copies bytes in and out so callers cannot mutate the cached blob`() {
        val cache = Cache.inMemory()
        val stored = byteArrayOf(1, 2, 3)
        cache.put("k", stored)
        stored[0] = 9 // mutate the caller's array after put
        cache.get("k")!![0] = 8 // mutate the returned array
        assertContentEquals(byteArrayOf(1, 2, 3), cache.get("k"))
    }

    // ── getOrComputeBytes / getOrCompute<T> ──────────────────────────────────────────────────────

    @Test
    fun `getOrComputeBytes computes once then serves from cache`() {
        val cache = Cache.inMemory()
        val computes = AtomicInteger(0)
        fun call() = cache.getOrComputeBytes("k") { computes.incrementAndGet(); "v".encodeToByteArray() }

        assertContentEquals("v".encodeToByteArray(), call())
        assertContentEquals("v".encodeToByteArray(), call())
        assertEquals(1, computes.get(), "compute must run only on the first call")
    }

    @Test
    fun `typed getOrCompute round-trips a serializable value and computes once`() {
        val cache = Cache.inMemory()
        val computes = AtomicInteger(0)
        fun call() = cache.getOrCompute<Meta>("meta") { computes.incrementAndGet(); Meta("jdk", 42) }

        assertEquals(Meta("jdk", 42), call())
        assertEquals(Meta("jdk", 42), call())
        assertEquals(1, computes.get())
    }

    // ── on-disk: file-per-key + persistence ──────────────────────────────────────────────────────

    @Test
    fun `on-disk writes one file per key and a fresh cache over the same root sees it`(@TempDir root: Path) {
        Cache.onDisk(root).put("the-key", "payload".encodeToByteArray())
        // one file written (plus no leftover temp files)
        val files = Files.list(root).use { it.toList() }
        assertEquals(1, files.size, "exactly one cache file expected, got $files")
        assertTrue(files.single().fileName.toString().none { it == '/' })
        // a brand-new Cache over the same root reads the persisted value
        assertContentEquals("payload".encodeToByteArray(), Cache.onDisk(root).get("the-key"))
        assertNull(Cache.onDisk(root).get("absent"))
    }

    // ── downloadWithEtag (built on getOrComputeBytes) ────────────────────────────────────────────

    /** Fake fetcher: serves [bytesByEtag] for the current [etag], counting actual downloads. */
    private class FakeFetcher(var etag: String?, val bytesByEtag: MutableMap<String?, ByteArray>) : HttpFetcher {
        val downloads = AtomicInteger(0)
        override fun head(url: String): UrlKey =
            UrlKey(url = url, size = bytesByEtag[etag]?.size?.toLong() ?: -1L, lastModified = null, etag = etag)

        override fun getBytes(url: String): ByteArray {
            downloads.incrementAndGet()
            return bytesByEtag.getValue(etag)
        }
    }

    @Test
    fun `downloadWithEtag re-downloads only when the ETag changes`() {
        val cache = Cache.inMemory()
        val http = FakeFetcher("v1", mutableMapOf("v1" to "data-1".encodeToByteArray(), "v2" to "data-2".encodeToByteArray()))
        val url = "https://example.com/jdk.tar.gz"

        assertContentEquals("data-1".encodeToByteArray(), cache.downloadWithEtag(url, http))
        assertContentEquals("data-1".encodeToByteArray(), cache.downloadWithEtag(url, http)) // same ETag -> cache hit
        assertEquals(1, http.downloads.get(), "unchanged ETag must NOT re-download")

        http.etag = "v2"
        assertContentEquals("data-2".encodeToByteArray(), cache.downloadWithEtag(url, http)) // new ETag -> re-download
        assertEquals(2, http.downloads.get())

        http.etag = "v1" // a previously-seen ETag is still cached -> no download
        assertContentEquals("data-1".encodeToByteArray(), cache.downloadWithEtag(url, http))
        assertEquals(2, http.downloads.get())
    }

    @Test
    fun `downloadWithEtag fails fast when the server exposes no ETag`() {
        val cache = Cache.inMemory()
        val http = FakeFetcher(null, mutableMapOf<String?, ByteArray>(null to "body".encodeToByteArray()))

        val ex = assertFailsWith<IllegalArgumentException> {
            cache.downloadWithEtag("https://example.com/no-etag.bin", http)
        }
        assertTrue(ex.message!!.contains("no ETag"), "message should explain the missing ETag: ${ex.message}")
        assertEquals(0, http.downloads.get(), "must not download when caching can't be validated")
    }
}
