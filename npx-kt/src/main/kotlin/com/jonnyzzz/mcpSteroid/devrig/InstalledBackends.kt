/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * A devrig-managed (installed under `~/.mcp-steroid/backends/`) IDE that can be started on demand.
 */
data class InstalledBackend(
    val id: String,
    val ide: IdeInfo,
    /** Absolute path to the IDE's install home — the same path `PathManager.getHomePath()` reports when running. */
    val ideHome: String,
    val launcher: Path,
)

private fun normalizeHome(p: String): String = Path.of(p).toAbsolutePath().normalize().toString()

/**
 * Returns the subset of [installed] backends that are not already running (i.e. no [DiscoveredIde]
 * in [running] has an `ideHome` that matches the backend's `ideHome`, path-normalized).
 */
fun startableBackends(
    installed: List<InstalledBackend>,
    running: List<DiscoveredIde>,
): List<InstalledBackend> {
    val runningHomes = running.mapNotNull { it.ideHome }.map(::normalizeHome).toSet()
    return installed.filter { normalizeHome(it.ideHome) !in runningHomes }
}

/**
 * Enumerates all devrig-managed IDEs installed under `~/.mcp-steroid/backends/`.
 *
 * Reuses [readDescriptorOrNull] and [descriptorPath] from [ManagedBackend.kt] — does not
 * duplicate JSON parsing. Backends with a missing or malformed descriptor are silently skipped
 * (logged to stderr).
 */
fun DevrigServices.installedBackends(): List<InstalledBackend> {
    if (!Files.isDirectory(homePaths.backendsDir)) return emptyList()
    return Files.list(homePaths.backendsDir).use { stream ->
        stream.asSequence()
            .filter { Files.isDirectory(it) }
            .mapNotNull { dir ->
                try {
                    val descriptor = readDescriptorOrNull(descriptorPath(dir)) ?: return@mapNotNull null
                    val bundleDir = homePaths.backendDir(descriptor.id).resolve(descriptor.bundleDirName)
                    if (!Files.isDirectory(bundleDir)) return@mapNotNull null
                    val launcher = bundleDir.resolve(descriptor.launcherPath)
                    val ide = IdeInfo(
                        name = descriptor.productKey,
                        version = descriptor.version,
                        build = descriptor.buildNumber ?: "",
                    )
                    InstalledBackend(
                        id = descriptor.id,
                        ide = ide,
                        ideHome = bundleDir.toAbsolutePath().normalize().toString(),
                        launcher = launcher,
                    )
                } catch (e: Exception) {
                    System.err.println("WARN: failed to read installed backend from $dir: ${e.message}")
                    null
                }
            }
            .sortedWith(compareBy({ it.ide.name }, { it.ide.version }))
            .toList()
    }
}
