/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

sealed class IdeDistribution {
    abstract val product: IdeProduct

    /**
     * Resolve the latest release of [product] on [channel] from the public products API.
     *
     * @param acceptPaid whether downloading a paid IDE ([LicenseTier.Paid]) is allowed.
     *  Defaults to `true` so existing call sites keep compiling and downloading paid IDEs
     *  unchanged. New callers that want a strict gate (CLI front-ends, build-time tasks
     *  that should never accidentally pull Ultimate) pass `false` and call
     *  [requirePaidConsent] before resolving the URL.
     */
    data class Latest(
        override val product: IdeProduct = IdeProduct.IntelliJIdea,
        val channel: IdeChannel = IdeChannel.STABLE,
        val acceptPaid: Boolean = true,
    ) : IdeDistribution()

    data class FromUrl(
        override val product: IdeProduct,
        val url: String,
        val fileName: String? = null,
    ) : IdeDistribution()

    /**
     * Throws when this distribution requests a [LicenseTier.Paid] product without explicit
     * consent. Free and free-for-non-commercial IDEs always pass. Call before kicking off
     * a download in a context where paid downloads should be opt-in.
     */
    fun requirePaidConsent() {
        if (product.licenseTier != LicenseTier.Paid) return
        val accepted = when (this) {
            is Latest -> acceptPaid
            is FromUrl -> true // explicit URL means the caller already chose deliberately
        }
        require(accepted) {
            "Refusing to download paid IDE '${product.displayName}' (${product.code}) without explicit consent. " +
                "Pass acceptPaid = true on IdeDistribution.Latest, or switch to a free SKU " +
                "(e.g. IdeProduct.IntelliJIdeaCommunity / IdeProduct.PyCharmCommunity)."
        }
    }
}
