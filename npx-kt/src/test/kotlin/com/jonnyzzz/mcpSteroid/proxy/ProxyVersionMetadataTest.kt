/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Asserts the generated [ProxyVersionMetadata] class carries the Gradle project
 * version that runtime banners, MCP handshakes, and update checks report.
 */
class ProxyVersionMetadataTest {

    @Test
    fun `loadProxyVersion returns the generated metadata value`() {
        val version = loadProxyVersion()
        assertTrue(version.isNotBlank(), "version must be non-blank, got: '$version'")
        assertEquals(ProxyVersionMetadata.getProxyVersion(), version)
    }

    @Test
    fun `version matches project_version system property when provided by the build`() {
        val expected = System.getProperty("npx-kt.expected.version") ?: return
        assertEquals(expected, loadProxyVersion())
    }
}
