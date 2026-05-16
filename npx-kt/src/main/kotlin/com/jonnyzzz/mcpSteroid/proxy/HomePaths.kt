/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.PidMarker
import java.nio.file.Files
import java.nio.file.Path

class HomePaths(val home: Path) {
    val logsDir: Path get() = home.resolve("logs")
    val backendsDir: Path get() = home.resolve("backends")
    val cachesDir: Path get() = home.resolve("caches")
    val downloadsDir: Path get() = home.resolve("downloads")
    val stateDir: Path get() = home.resolve("state")
    val markersDir: Path get() = home.resolve("markers")
    val executionStorageDir: Path get() = home.resolve("execution-storage")

    fun backendDir(id: String): Path = backendsDir.resolve(id)
    fun cacheDir(id: String): Path = cachesDir.resolve(id)
    fun pidFile(id: String): Path = stateDir.resolve("$id.pid")

    fun mkdirsAll() {
        listOf(logsDir, backendsDir, cachesDir, downloadsDir, stateDir, markersDir).forEach { Files.createDirectories(it) }
    }
}

fun resolveHomePaths(
    override: String?,
    env: Map<String, String> = System.getenv(),
): HomePaths {
    val raw = override?.let(::expandTilde)
        ?: PidMarker.markerHomeDirectory(Path.of(System.getProperty("user.home")), env).toString()
    val path = Path.of(raw)
    rejectDotDot(path)
    return HomePaths(path.toAbsolutePath().normalize())
}

private fun expandTilde(raw: String): String {
    if (raw == "~") return System.getProperty("user.home")
    if (raw.startsWith("~/")) return System.getProperty("user.home") + raw.substring(1)
    if (raw.startsWith("~")) {
        throw IllegalArgumentException("--home: unsupported '~user' form. Use \"${'$'}HOME\" or an absolute path.")
    }
    return raw
}

private fun rejectDotDot(path: Path) {
    val hasDotDot = path.iterator().asSequence().any { it.toString() == ".." }
    if (hasDotDot) {
        throw IllegalArgumentException("--home: '..' segments are not allowed; pass an explicit absolute path.")
    }
}
