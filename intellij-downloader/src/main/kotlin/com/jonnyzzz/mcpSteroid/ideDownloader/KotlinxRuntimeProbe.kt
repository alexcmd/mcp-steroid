/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Runtime classloader probe — launched by `VerifyBundledKotlinxRuntimeTask`
 * with a classpath of *only* every jar under the IDE's lib dir plus this jar (no
 * kotlinx-coroutines / kotlinx-serialization shipped alongside). Validates
 * that the plugin's compile-time kotlinx pins are link-compatible with
 * what IntelliJ 261 bundles inside `lib/intellij.libraries.kotlinx.*.jar`.
 *
 * Per the JetBrains rule
 * (https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#coroutinesLibraries),
 * the plugin must use the IDE-bundled `kotlinx-coroutines` at runtime —
 * never ship its own. Our `configurations.implementation.exclude(group = "org.jetbrains.kotlinx")`
 * already prevents bundling, but a compile-time-only API drift (e.g.
 * kotlinx-serialization adding a method we call) would only surface at
 * runtime in the IDE. This probe is the build-time gate that fails fast.
 *
 * The probe exercises:
 *  - `kotlinx.serialization.json.Json.encodeToString(...)` over a `@Serializable`
 *    data class (touches the codegen + runtime + json modules).
 *  - `kotlinx.coroutines.runBlocking { delay(1) }` (touches the structured
 *    concurrency + dispatcher path).
 *
 * On success it prints `RUNTIME_PROBE_OK: ...` and exits 0. Any
 * `LinkageError` / `NoClassDefFoundError` / `IncompatibleClassChangeError`
 * surfaces as a non-zero exit, which the Gradle task interprets as a build
 * failure.
 */
@Serializable
internal data class KotlinxRuntimeProbeMessage(
    val ide: String,
    val build: String,
    val kotlinSerializationOk: Boolean,
)

object KotlinxRuntimeProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val ide = args.getOrNull(0) ?: "unknown"
        val build = args.getOrNull(1) ?: "unknown"

        val payload = KotlinxRuntimeProbeMessage(
            ide = ide,
            build = build,
            kotlinSerializationOk = true,
        )
        val json = Json.encodeToString(payload)
        println("RUNTIME_PROBE_JSON_OK: $json")

        val coroutinesResult = runBlocking {
            delay(1)
            "coroutines-ok"
        }
        println("RUNTIME_PROBE_COROUTINES_OK: $coroutinesResult")

        println("RUNTIME_PROBE_OK")
    }
}
