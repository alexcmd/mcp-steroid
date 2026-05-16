/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

const val DEVRIG_HOME_ENV: String = "DEVRIG_HOME"

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

fun resolveHomePaths(): HomePaths = resolveHomePathsFromEnvironment(
    env = System.getenv(),
    err = System.err,
)

fun resolveHomePathsFromEnvironment(
    env: Map<String, String>,
    err: PrintStream?,
): HomePaths {
    val override = env[DEVRIG_HOME_ENV]?.takeIf { it.isNotBlank() }
    val path = if (override == null) {
        Path.of(System.getProperty("user.home"), ".mcp-steroid")
    } else {
        err?.println("Using $DEVRIG_HOME_ENV as devrig home override.")
        canonicalOverridePath(override)
    }
    return HomePaths(path.toAbsolutePath().normalize())
}

fun resolveHomePathsOrDie(): HomePaths {
    try {
        val homePaths = resolveHomePaths()
        homePaths.mkdirsAll()
        return homePaths
    } catch (e: Throwable) {
        System.err.println(e.message)
        e.printStackTrace(System.err)
        exitProcess(64)
    }
}

private fun canonicalOverridePath(raw: String): Path {
    val path = Path.of(raw)
    if (!path.isAbsolute) {
        throw IllegalArgumentException("$DEVRIG_HOME_ENV: override must be an existing absolute path.")
    }
    try {
        return path.toRealPath()
    } catch (e: Exception) {
        throw IllegalArgumentException("$DEVRIG_HOME_ENV: cannot resolve canonical path for '$raw'.", e)
    }
}
