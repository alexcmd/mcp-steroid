/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

internal data class LauncherResolution(
    val launcherPath: String,
    val launcherAbsolutePath: Path,
    val productCode: String?,
    val buildNumber: String?,
)

internal class LauncherResolver(
    private val hostOs: HostOs = resolveHostOs(),
) {
    fun resolve(bundleDir: Path): LauncherResolution {
        val productInfoPath = productInfoPath(bundleDir)
        val productInfo = productInfoJson.decodeFromString<ProductInfo>(Files.readString(productInfoPath))
        val osName = hostOs.productInfoOsName()
        val launch = productInfo.launch.firstOrNull { it.os == osName }
            ?: error(
                "product-info.json at $productInfoPath has no launch entry for $osName. " +
                    "Available OS entries: ${productInfo.launch.joinToString { it.os }}"
            )
        require(launch.launcherPath.isNotBlank()) {
            "product-info.json at $productInfoPath has an empty launcherPath for $osName"
        }
        return LauncherResolution(
            launcherPath = launch.launcherPath,
            launcherAbsolutePath = bundleDir.resolve(launch.launcherPath).normalize(),
            productCode = productInfo.productCode,
            buildNumber = productInfo.buildNumber,
        )
    }

    private fun productInfoPath(bundleDir: Path): Path {
        val candidates = listOf(
            bundleDir.resolve("product-info.json"),
            bundleDir.resolve("Contents/Resources/product-info.json"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "product-info.json not found under $bundleDir. " +
                    "Checked: ${candidates.joinToString()}"
            )
    }
}

private fun HostOs.productInfoOsName(): String = when (this) {
    HostOs.MAC -> "macOS"
    HostOs.LINUX -> "Linux"
    HostOs.WINDOWS -> "Windows"
}

@Serializable
private data class ProductInfo(
    val productCode: String? = null,
    val buildNumber: String? = null,
    val launch: List<ProductLaunch> = emptyList(),
)

@Serializable
private data class ProductLaunch(
    val os: String,
    val launcherPath: String,
)

private val productInfoJson = Json {
    ignoreUnknownKeys = true
}
