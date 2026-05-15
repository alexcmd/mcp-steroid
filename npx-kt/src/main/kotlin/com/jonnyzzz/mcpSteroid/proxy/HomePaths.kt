/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.PidMarker
import java.nio.file.Files
import java.nio.file.Path

internal class HomePaths(val home: Path) {
    val logsDir: Path get() = home.resolve("logs")
    val backendsDir: Path get() = home.resolve("backends")
    val cachesDir: Path get() = home.resolve("caches")
    val stateDir: Path get() = home.resolve("state")
    val markersDir: Path get() = home.resolve("markers")
    val executionStorageDir: Path get() = home.resolve("execution-storage")

    fun backendDir(id: String): Path = backendsDir.resolve(id)
    fun cacheDir(id: String): Path = cachesDir.resolve(id)
    fun pidFile(id: String): Path = stateDir.resolve("$id.pid")

    fun mkdirsAll() {
        listOf(logsDir, backendsDir, cachesDir, stateDir, markersDir).forEach { Files.createDirectories(it) }
    }
}

internal fun resolveHomePaths(
    override: String?,
    env: Map<String, String> = System.getenv(),
): HomePaths {
    val raw = override
        ?: PidMarker.markerHomeDirectory(Path.of(System.getProperty("user.home")), env).toString()
    return HomePaths(Path.of(raw).toAbsolutePath().normalize())
}
