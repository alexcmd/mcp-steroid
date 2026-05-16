package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import java.io.InputStream
import java.io.PrintStream

class NpxKtServices(
    private val lifetime: CloseableStack,

    val homePaths: HomePaths,
    val mcpStdin: InputStream,
    val mcpStdout: PrintStream,
) {
    fun lifetime(name: String): CloseableStack = lifetime.nestedStack(name)

    val beacon by lazy {
        NpxBeacon(homePaths, lifetime)
    }
}
