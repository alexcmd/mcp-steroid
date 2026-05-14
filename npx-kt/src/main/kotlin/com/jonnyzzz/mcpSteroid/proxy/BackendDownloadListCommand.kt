/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeChannel
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.LicenseTier
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveArchive
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal data class AvailableBackendDownload(
    val product: IdeProduct,
    val version: String?,
    val versionLookupError: String? = null,
) {
    val requiresAllowPaid: Boolean get() = product.licenseTier == LicenseTier.Paid
}

internal interface AvailableBackendVersionResolver {
    suspend fun resolveLatestStableVersion(product: IdeProduct): String
}

internal class ReleaseServiceAvailableBackendVersionResolver(
    private val os: HostOs = resolveHostOs(),
) : AvailableBackendVersionResolver {
    override suspend fun resolveLatestStableVersion(product: IdeProduct): String = withContext(Dispatchers.IO) {
        resolveArchive(
            product = product,
            channel = IdeChannel.STABLE,
            os = os,
            preferWindowsZip = true,
            version = null,
        ).version
    }
}

internal fun runBackendDownloadListCommand(
    out: PrintStream,
    json: Boolean,
    versionResolver: AvailableBackendVersionResolver = ReleaseServiceAvailableBackendVersionResolver(),
) {
    val rows = runBlocking(Dispatchers.IO) {
        collectAvailableBackendDownloads(versionResolver = versionResolver)
    }
    if (json) {
        renderBackendDownloadListJson(rows, out)
    } else {
        renderBackendDownloadListText(rows, out)
    }
}

internal suspend fun collectAvailableBackendDownloads(
    products: List<IdeProduct> = orderedKnownBackendProducts(),
    versionResolver: AvailableBackendVersionResolver,
    totalBudget: Duration = 15.seconds,
): List<AvailableBackendDownload> = coroutineScope {
    products.map { product ->
        async {
            if (product.licenseTier == LicenseTier.Paid) {
                AvailableBackendDownload(product = product, version = null)
            } else {
                val version = tryResolveLatestStableVersion(product, versionResolver, totalBudget)
                AvailableBackendDownload(
                    product = product,
                    version = version.getOrNull(),
                    versionLookupError = version.exceptionOrNull()?.shortMessage(),
                )
            }
        }
    }.awaitAll()
}

private suspend fun tryResolveLatestStableVersion(
    product: IdeProduct,
    versionResolver: AvailableBackendVersionResolver,
    timeout: Duration,
): Result<String> {
    val version = try {
        withTimeoutOrNull(timeout) {
            versionResolver.resolveLatestStableVersion(product)
        } ?: return Result.failure(IllegalStateException("timed out after ${timeout.inWholeSeconds}s"))
    } catch (e: Exception) {
        return Result.failure(e)
    }
    return Result.success(version)
}

private fun orderedKnownBackendProducts(): List<IdeProduct> {
    val originalIndex = IdeProduct.knownProducts.withIndex().associate { it.value to it.index }
    return IdeProduct.knownProducts.sortedWith(
        compareBy<IdeProduct> { licenseTierSortKey(it.licenseTier) }
            .thenBy { originalIndex.getValue(it) },
    )
}

private fun licenseTierSortKey(tier: LicenseTier): Int = when (tier) {
    LicenseTier.Free -> 0
    LicenseTier.FreeForNonCommercial -> 1
    LicenseTier.Paid -> 2
}

internal fun renderBackendDownloadListText(rows: List<AvailableBackendDownload>, out: PrintStream) {
    out.println("$BRAND_NAME v${loadProxyVersion()} — $BRAND_TAGLINE")
    out.println()
    out.println("Available IDEs (defaults to latest stable):")
    out.println()
    if (rows.isNotEmpty()) {
        val indexWidth = rows.size.toString().length + 2
        val idWidth = rows.maxOf { it.product.id.length }
        val displayWidth = rows.maxOf { it.product.displayName.length }
        val versionWidth = rows
            .filterNot { it.requiresAllowPaid }
            .map { it.versionText().length }
            .maxOrNull() ?: 0
        for ((index, row) in rows.withIndex()) {
            val indexLabel = "[${index + 1}]".padEnd(indexWidth)
            val id = row.product.id.padEnd(idWidth)
            val name = row.product.displayName.padEnd(displayWidth)
            if (row.requiresAllowPaid) {
                out.println("  $indexLabel $id  $name  (paid — requires --allow-paid)")
            } else {
                out.println("  $indexLabel $id  $name  ${row.versionText().padEnd(versionWidth)}  ${row.product.licenseTier.cliValue}")
            }
        }
    }
    out.println()
    out.println("Run:  devrig backend download <id> [--version <v>] [--allow-paid]")
    out.println()
}

internal fun renderBackendDownloadListJson(rows: List<AvailableBackendDownload>, out: PrintStream) {
    val payload = buildJsonObject {
        putToolJson()
        put("available", buildJsonArray {
            for (row in rows) {
                add(buildJsonObject {
                    put("id", row.product.id)
                    put("code", row.product.code)
                    put("displayName", row.product.displayName)
                    put("licenseTier", row.product.licenseTier.cliValue)
                    if (row.version == null) put("version", JsonNull) else put("version", row.version)
                    put("requiresAllowPaid", row.requiresAllowPaid)
                    row.versionLookupError?.let { put("versionLookupError", it) }
                })
            }
        })
    }
    out.println(backendPrettyJson.encodeToString(JsonObject.serializer(), payload))
}

private fun AvailableBackendDownload.versionText(): String =
    version ?: "(version lookup failed: ${versionLookupError ?: "unknown"})"

internal val LicenseTier.cliValue: String
    get() = when (this) {
        LicenseTier.Free -> "free"
        LicenseTier.FreeForNonCommercial -> "free-for-non-commercial"
        LicenseTier.Paid -> "paid"
    }

internal fun Throwable.shortMessage(): String {
    val raw = message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "failed"
    return raw.take(140)
}

internal val backendPrettyJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
}

internal fun kotlinx.serialization.json.JsonObjectBuilder.putToolJson() {
    put("tool", buildJsonObject {
        put("name", BRAND_NAME)
        put("version", loadProxyVersion())
    })
}
