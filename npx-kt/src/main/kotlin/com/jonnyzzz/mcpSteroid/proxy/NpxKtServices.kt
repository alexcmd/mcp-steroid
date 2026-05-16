package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack

class NpxKtServices(
    val homePaths: HomePaths,
    val args: NpxKtArgs,
    private val lifetime: CloseableStack
) {
    fun lifetime(name: String): CloseableStack = lifetime.nestedStack(name)

    val version by lazy { ProxyVersionMetadata.getProxyVersion() }

    val beacon by lazy {
        NpxBeacon(proxyVersion = version).also { lifetime.registerCleanupAction { it.close() } }
    }
}
