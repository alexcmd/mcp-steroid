/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Resolves the `npx-kt` installDist root — the directory that contains `lib/` (the runtime classpath),
 * `bin/` (the launcher scripts), and the bundled package subtrees (`ij-plugin/`, `7z/`).
 *
 * Hard requirement: this function MUST succeed when called from any production class in the proxy.
 * If it cannot locate the root, throw an [IllegalStateException] with the diagnostic path it inspected;
 * there is no fallback. Callers may rely on [path].resolve("7z") and [path].resolve("ij-plugin")
 * existing on disk.
 */
object NpxKtRoot {
    private val lock = Any()

    @Volatile
    private var cachedPath: Path? = null

    internal var codeSourcePathOverride: Path? = null

    /** Lazy-evaluated; cached for the JVM lifetime. */
    val path: Path
        get() = cachedPath ?: synchronized(lock) {
            cachedPath ?: resolveOrFail().also { cachedPath = it }
        }

    fun sevenZipDir(): Path = path.resolve("7z")

    fun ijPluginDir(): Path = path.resolve("ij-plugin")

    internal fun resetForTests() {
        synchronized(lock) {
            cachedPath = null
        }
    }

    private fun resolveOrFail(): Path {
        val codeSourcePath = codeSourcePathOverride ?: realCodeSourcePath()
        val candidate = walkUpForInstallDistRoot(codeSourcePath)
            ?: error(
                "Cannot resolve npx-kt root from $codeSourcePath. " +
                    "Expected to find a parent dir containing both 'lib/' and at least " +
                    "one of 'ij-plugin/' or '7z/'. The proxy must be launched via " +
                    "the installDist tree (`./npx-kt/build/install/mcp-steroid-proxy/bin/...`)."
            )

        check(candidate.resolve("lib").isDirectory()) {
            "npx-kt root inference produced $candidate, but $candidate/lib is missing"
        }
        return candidate
    }

    private fun realCodeSourcePath(): Path {
        val codeSourceUrl = NpxKtRoot::class.java.protectionDomain
            ?.codeSource
            ?.location
            ?: error(
                "Cannot resolve npx-kt root: ProtectionDomain.codeSource.location is null. " +
                    "This usually means the class was loaded via a non-standard ClassLoader."
            )

        return try {
            Path.of(codeSourceUrl.toURI())
        } catch (e: Exception) {
            error("Cannot resolve npx-kt root: $codeSourceUrl is not a file URI: ${e.message}")
        }
    }

    /**
     * Walk parent dirs of [start] up to the filesystem root. Return the first parent that has `lib/`
     * and at least one bundled package subtree. Return `null` if no such parent exists.
     */
    private fun walkUpForInstallDistRoot(start: Path): Path? {
        var p: Path? = start.parent
        while (p != null) {
            val hasLib = p.resolve("lib").isDirectory()
            val hasPackageSubtree = p.resolve("ij-plugin").isDirectory() || p.resolve("7z").isDirectory()
            if (hasLib && hasPackageSubtree) return p
            p = p.parent
        }
        return null
    }
}
