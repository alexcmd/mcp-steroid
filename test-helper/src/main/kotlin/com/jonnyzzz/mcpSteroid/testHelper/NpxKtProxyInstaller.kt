/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File

/**
 * Deploys the Kotlin `npx-kt` MCP proxy application distribution into an IDE test
 * container and returns the stdio command agents should register.
 */
fun ContainerDriver.prepareNpxKtProxyFromZipFile(
    npxKtZipFile: File,
    ideMcpUrl: String,
    userHome: String,
): StdioMcpCommand {
    require(npxKtZipFile.isFile) {
        "npx-kt distribution ZIP is missing at ${npxKtZipFile.absolutePath}"
    }

    val guestDir = "/tmp/mcp-steroid-npx-kt"
    val guestZip = "$guestDir/${npxKtZipFile.name}"
    val guestConfig = "$guestDir/proxy.json"
    val guestRoot = "$guestDir/${npxKtZipFile.name.removeSuffix(".zip")}"
    val guestCommand = "$guestRoot/bin/mcp-steroid-proxy"
    val markerPath = "$userHome/.1.mcp-steroid"
    val bridgeMetadata = fetchBuiltInRpcDeploymentMetadata(ideMcpUrl)

    mkdirs(guestDir).assertExitCode(0) {
        "Failed to create npx-kt deployment directory $guestDir: $stderr"
    }
    copyToContainer(npxKtZipFile, guestZip)

    startProcessInContainer {
        this
            .args(
                "bash", "-lc",
                """
                set -euo pipefail
                rm -rf ${shellQuote(guestRoot)}
                unzip -q ${shellQuote(guestZip)} -d ${shellQuote(guestDir)}
                test -x ${shellQuote(guestCommand)}
                """.trimIndent()
            )
            .timeoutSeconds(60)
            .description("Unpack npx-kt proxy distribution")
            .quietly()
    }.assertExitCode(0) {
        "Failed to unpack npx-kt proxy distribution $guestZip into $guestDir: $stderr"
    }

    writeFileInContainer(
        guestConfig,
        """
        {
          "homeDir": "$userHome",
          "scanIntervalMs": 500,
          "allowHosts": ["host.docker.internal", "localhost", "127.0.0.1"],
          "upstreamTimeoutMs": 15000,
          "updates": { "enabled": false },
          "beacon": { "enabled": false }
        }
        """.trimIndent(),
        executable = false,
    )

    writeFileInContainer(
        markerPath,
        listOf(
            ideMcpUrl,
            "",
            "IntelliJ MCP Steroid Server",
            "URL: $ideMcpUrl",
            "Built-in RPC URL: ${bridgeMetadata.builtInRpcUrl}",
            "Built-in RPC Token: ${bridgeMetadata.rpcToken}",
        ).joinToString(separator = "\n", postfix = "\n"),
        executable = false,
    )

    startProcessInContainer {
        this
            .args(guestCommand, "--help")
            .timeoutSeconds(20)
            .description("Verify npx-kt proxy command")
            .quietly()
    }.assertExitCode(0) {
        "npx-kt proxy command failed --help verification at $guestCommand: $stderr"
    }

    return StdioMcpCommand(
        command = guestCommand,
        args = listOf("--config", guestConfig),
    )
}

private data class BuiltInRpcDeploymentMetadata(
    val builtInRpcUrl: String,
    val rpcToken: String,
)

private fun ContainerDriver.fetchBuiltInRpcDeploymentMetadata(ideMcpUrl: String): BuiltInRpcDeploymentMetadata {
    val result = startProcessInContainer {
        this
            .args(
                "bash", "-lc",
                """
                set -euo pipefail
                for marker in /home/agent/.[0-9]*.mcp-steroid; do
                  [ -f "${'$'}marker" ] || continue
                  first_line="${'$'}(head -n 1 "${'$'}marker")"
                  if [ "${'$'}first_line" = ${shellQuote(ideMcpUrl)} ] || grep -Fxq ${shellQuote("URL: $ideMcpUrl")} "${'$'}marker"; then
                    cat "${'$'}marker"
                    exit 0
                  fi
                done
                echo "No MCP Steroid marker for ${shellQuote(ideMcpUrl)} under /home/agent" >&2
                exit 1
                """.trimIndent(),
            )
            .timeoutSeconds(20)
            .description("Read MCP Steroid built-in RPC metadata from marker file")
            .quietly()
    }.awaitForProcessFinish()

    val marker = result.stdout
    val builtInRpcUrl = marker.markerValue("Built-in RPC URL:")
    val rpcToken = marker.markerValue("Built-in RPC Token:", "NPX Token:")
    if (result.exitCode != 0) {
        System.err.println(
            "MCP Steroid marker lookup for $ideMcpUrl returned exit code ${result.exitCode}; " +
                "continuing because stdout contained marker data. stderr=${result.stderr.take(400)}"
        )
    }
    validateBuiltInRpcEndpoint(builtInRpcUrl, rpcToken, ideMcpUrl)
    return BuiltInRpcDeploymentMetadata(
        builtInRpcUrl = builtInRpcUrl,
        rpcToken = rpcToken,
    )
}

private fun ContainerDriver.validateBuiltInRpcEndpoint(
    builtInRpcUrl: String,
    rpcToken: String,
    ideMcpUrl: String,
) {
    val result = startProcessInContainer {
        this
            .args(
                "curl",
                "-fsS",
                "-H",
                "Authorization: Bearer $rpcToken",
                "$builtInRpcUrl/server-metadata",
            )
            .timeoutSeconds(20)
            .description("Validate MCP Steroid built-in RPC metadata endpoint")
            .quietly()
    }.assertExitCode(0) {
        "MCP Steroid built-in RPC endpoint is not reachable at $builtInRpcUrl: $stderr"
    }

    require(result.stdout.contains(ideMcpUrl.removeSuffix("/mcp"))) {
        "MCP Steroid built-in RPC metadata response did not identify the expected IDE MCP server. " +
            "Expected URL prefix ${ideMcpUrl.removeSuffix("/mcp")}, response: ${result.stdout.take(500)}"
    }
}

private fun String.markerValue(vararg prefixes: String): String =
    lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            prefixes.firstOrNull { prefix -> line.startsWith(prefix, ignoreCase = true) }
                ?.let { prefix -> line.substring(prefix.length).trim() }
        }
        .firstOrNull { it.isNotBlank() }
        ?: error("MCP Steroid marker is missing one of ${prefixes.joinToString()}:\n$this")

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
