/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

fun loadProxyVersion(): String {
    return try {
        val resource = object {}.javaClass.classLoader.getResourceAsStream("proxy-version.txt")
        resource?.bufferedReader()?.readText()?.trim() ?: "0.1.0"
    } catch (e: Exception) {
        throw Error("Failed to load proxy version: ${e.message}", e)
    }
}
