package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack

class NpxKtServices(
    val homePaths: HomePaths,
    val args: NpxKtArgs,
    private val lifetime: CloseableStack
) {
    fun lifetime(name: String): CloseableStack = lifetime.nestedStack(name)

    val beacon by lazy {
        NpxBeacon(homePaths).also { lifetime.registerCleanupAction { it.close() } }
    }
}
