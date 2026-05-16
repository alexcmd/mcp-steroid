/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

fun loadProxyVersion(): String {
    return try {
        ProxyVersionMetadata.getProxyVersion()
    } catch (e: Exception) {
        throw Error("Failed to load proxy version: ${e.message}", e)
    }
}
