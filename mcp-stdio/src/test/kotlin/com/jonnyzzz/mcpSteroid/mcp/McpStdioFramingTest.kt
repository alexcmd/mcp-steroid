/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class McpStdioFramingTest {

    // -------------------------------------------------------------------------
    // readNextFrame — Content-Length framing
    // -------------------------------------------------------------------------

    @Test
    fun `empty buffer returns null`() {
        val buf = FramingBuffer()
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `framed message with CRLF separator is parsed`() {
        val buf = FramingBuffer()
        val body = """{"jsonrpc":"2.0","id":"1","method":"ping"}"""
        val bytes = encodeFramedMessage(body).toByteArray(Charsets.UTF_8)
        buf.append(bytes)
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
        assertEquals("framed", frame.mode)
    }

    @Test
    fun `framed message with LF-only separator is parsed`() {
        val buf = FramingBuffer()
        val body = """{"id":1}"""
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bodyBytes.size}\n\n"
        buf.append(header.toByteArray(Charsets.UTF_8))
        buf.append(bodyBytes)
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
        assertEquals("framed", frame.mode)
    }

    @Test
    fun `partial headers return null until separator arrives`() {
        val buf = FramingBuffer()
        buf.append("Content-Length: 5".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `partial body returns null until full payload arrives`() {
        val buf = FramingBuffer()
        val header = "Content-Length: 20\r\n\r\n".toByteArray(Charsets.UTF_8)
        buf.append(header)
        buf.append("partial".toByteArray(Charsets.UTF_8))  // only 7 of 20 bytes
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `full body after partial delivers frame`() {
        val buf = FramingBuffer()
        val body = "12345678901234567890"  // exactly 20 chars (ASCII)
        val header = "Content-Length: 20\r\n\r\n".toByteArray(Charsets.UTF_8)
        buf.append(header)
        buf.append("12345".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
        buf.append("678901234567890".toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `two framed messages read in sequence`() {
        val buf = FramingBuffer()
        val msg1 = """{"id":"1"}"""
        val msg2 = """{"id":"2"}"""
        buf.append(encodeFramedMessage(msg1).toByteArray(Charsets.UTF_8))
        buf.append(encodeFramedMessage(msg2).toByteArray(Charsets.UTF_8))

        val frame1 = buf.readNextFrame()
        assertNotNull(frame1)
        assertEquals(msg1, frame1.payloadText)

        val frame2 = buf.readNextFrame()
        assertNotNull(frame2)
        assertEquals(msg2, frame2.payloadText)

        assertNull(buf.readNextFrame())
    }

    @Test
    fun `consumed bytes are removed from buffer after read`() {
        val buf = FramingBuffer()
        val msg = """{"id":"1"}"""
        buf.append(encodeFramedMessage(msg).toByteArray(Charsets.UTF_8))
        buf.readNextFrame()
        assertTrue(buf.isEmpty())
    }

    @Test
    fun `buffer not empty until frame is read`() {
        val buf = FramingBuffer()
        buf.append("Content-Length: 5\r\n\r\nhe".toByteArray(Charsets.UTF_8))
        assertFalse(buf.isEmpty())
    }

    @Test
    fun `content-length uses byte count for unicode multibyte chars`() {
        val buf = FramingBuffer()
        val body = "α"  // U+03B1, 2 bytes in UTF-8
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bodyBytes.size}\r\n\r\n"
        buf.append(header.toByteArray(Charsets.UTF_8))
        buf.append(bodyBytes)
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `unicode payload fully preserved in framed mode`() {
        val buf = FramingBuffer()
        val body = """{"text":"Hello 世界 🌍"}"""
        buf.append(encodeFramedMessage(body).toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `frame result records framed mode`() {
        val buf = FramingBuffer()
        buf.append(encodeFramedMessage("{}").toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals("framed", frame.mode)
    }

    // -------------------------------------------------------------------------
    // readNextFrame — NDJSON framing
    // -------------------------------------------------------------------------

    @Test
    fun `ndjson message terminated by newline is parsed`() {
        val buf = FramingBuffer()
        val body = """{"jsonrpc":"2.0","id":"1","method":"ping"}"""
        buf.append(encodeNdjsonMessage(body).toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body.trim(), frame.payloadText)
        assertEquals("ndjson", frame.mode)
    }

    @Test
    fun `partial ndjson line without newline returns null`() {
        val buf = FramingBuffer()
        buf.append("""{"jsonrpc":"2.0""".toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `ndjson frame delivered after newline appended`() {
        val buf = FramingBuffer()
        val body = """{"id":"1"}"""
        buf.append(body.toByteArray(Charsets.UTF_8))
        assertNull(buf.readNextFrame())
        buf.append("\n".toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
    }

    @Test
    fun `two ndjson messages read in sequence`() {
        val buf = FramingBuffer()
        val msg1 = """{"id":"1"}"""
        val msg2 = """{"id":"2"}"""
        buf.append(encodeNdjsonMessage(msg1).toByteArray(Charsets.UTF_8))
        buf.append(encodeNdjsonMessage(msg2).toByteArray(Charsets.UTF_8))

        val frame1 = buf.readNextFrame()
        assertNotNull(frame1)
        assertEquals(msg1, frame1.payloadText)
        assertEquals("ndjson", frame1.mode)

        val frame2 = buf.readNextFrame()
        assertNotNull(frame2)
        assertEquals(msg2, frame2.payloadText)

        assertNull(buf.readNextFrame())
    }

    @Test
    fun `ndjson mode not triggered for non-json start`() {
        val buf = FramingBuffer()
        buf.append("plain text\n".toByteArray(Charsets.UTF_8))
        // No Content-Length header, doesn't start with { or [ => no NDJSON parsing
        assertNull(buf.readNextFrame())
    }

    @Test
    fun `ndjson array message is parsed`() {
        val buf = FramingBuffer()
        val body = """[{"id":"1"},{"id":"2"}]"""
        buf.append(encodeNdjsonMessage(body).toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body, frame.payloadText)
        assertEquals("ndjson", frame.mode)
    }

    @Test
    fun `unicode payload fully preserved in ndjson mode`() {
        val buf = FramingBuffer()
        val body = """{"text":"こんにちは 🎌"}"""
        buf.append(encodeNdjsonMessage(body).toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(body.trim(), frame.payloadText)
    }

    @Test
    fun `frame result records ndjson mode`() {
        val buf = FramingBuffer()
        buf.append(encodeNdjsonMessage("{}").toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals("ndjson", frame.mode)
    }

    // -------------------------------------------------------------------------
    // encodeFramedMessage
    // -------------------------------------------------------------------------

    @Test
    fun `encodeFramedMessage produces Content-Length header`() {
        val encoded = encodeFramedMessage("""{"id":1}""")
        assertTrue(encoded.startsWith("Content-Length:"))
    }

    @Test
    fun `encodeFramedMessage content length matches utf8 byte count`() {
        val body = "α"  // 2 UTF-8 bytes
        val encoded = encodeFramedMessage(body)
        val lengthLine = encoded.lines().first()
        val claimedLength = lengthLine.substringAfter("Content-Length:").trim().toInt()
        assertEquals(2, claimedLength)
    }

    @Test
    fun `encodeFramedMessage uses CRLF separator`() {
        val encoded = encodeFramedMessage("{}")
        assertTrue(encoded.contains("\r\n\r\n"), "Expected CRLF separator")
    }

    @Test
    fun `encodeFramedMessage round-trip via FramingBuffer`() {
        val original = """{"jsonrpc":"2.0","id":"42","method":"ping"}"""
        val encoded = encodeFramedMessage(original)
        val buf = FramingBuffer()
        buf.append(encoded.toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(original, frame.payloadText)
    }

    // -------------------------------------------------------------------------
    // encodeNdjsonMessage
    // -------------------------------------------------------------------------

    @Test
    fun `encodeNdjsonMessage appends trailing newline`() {
        val encoded = encodeNdjsonMessage("{}")
        assertTrue(encoded.endsWith("\n"))
    }

    @Test
    fun `encodeNdjsonMessage does not add Content-Length`() {
        val encoded = encodeNdjsonMessage("{}")
        assertFalse(encoded.contains("Content-Length"))
    }

    @Test
    fun `encodeNdjsonMessage round-trip via FramingBuffer`() {
        val original = """{"jsonrpc":"2.0","id":"1","method":"ping"}"""
        val encoded = encodeNdjsonMessage(original)
        val buf = FramingBuffer()
        buf.append(encoded.toByteArray(Charsets.UTF_8))
        val frame = buf.readNextFrame()
        assertNotNull(frame)
        assertEquals(original, frame.payloadText)
    }
}
