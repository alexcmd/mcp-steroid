/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct

internal data class BackendId(
    val product: IdeProduct,
    val version: String?,
) {
    val productKey: String get() = product.id

    fun withVersionOverride(versionOverride: String?): BackendId {
        return if (versionOverride.isNullOrBlank()) this else copy(version = validateBackendVersion(versionOverride))
    }
}

internal data class ResolvedBackendId(
    val product: IdeProduct,
    val version: String,
) {
    val id: String get() = "${product.id}-$version"
}

internal fun parseBackendId(raw: String): BackendId {
    require(raw.isNotBlank()) { "Backend id must not be blank" }

    val colonParts = raw.split(':')
    if (colonParts.size > 2) {
        throw IllegalArgumentException("Backend id '$raw' must contain at most one ':'")
    }
    if (colonParts.size == 2) {
        val product = parseManagedProductKey(colonParts[0])
        return BackendId(product, validateBackendVersion(colonParts[1]))
    }

    parseManagedProductKeyOrNull(raw)?.let { return BackendId(it, version = null) }

    val product = IdeProduct.knownProducts
        .sortedByDescending { it.id.length }
        .firstOrNull { raw.startsWith("${it.id}-") }
        ?: throw IllegalArgumentException(
            "Unsupported backend product in '$raw'. Known product keys: ${knownManagedProductKeys()}"
        )
    val version = raw.removePrefix("${product.id}-")
    return BackendId(product, validateBackendVersion(version))
}

internal fun validateBackendVersion(raw: String): String {
    val version = raw.trim()
    require(version.isNotBlank()) { "Backend version must not be blank" }
    require(version.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
        "Backend version '$raw' contains unsupported characters. " +
            "Use letters, digits, '.', '_' or '-'."
    }
    return version
}

private fun parseManagedProductKey(raw: String): IdeProduct {
    return parseManagedProductKeyOrNull(raw)
        ?: throw IllegalArgumentException(
            "Unsupported backend product '$raw'. Known product keys: ${knownManagedProductKeys()}"
        )
}

private fun parseManagedProductKeyOrNull(raw: String): IdeProduct? {
    return IdeProduct.knownProducts.firstOrNull { it.id == raw }
}

private fun knownManagedProductKeys(): String = IdeProduct.knownProducts.joinToString { it.id }
