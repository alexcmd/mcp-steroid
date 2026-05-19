/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.McpResourceUris
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end smoke test for the Kotlin `npx-kt` MCP proxy deployment path.
 *
 * The test starts IntelliJ in Docker, deploys the `:npx-kt` application ZIP into
 * the same container, registers that stdio command with Claude, and verifies the
 * agent can call MCP Steroid tools through the proxy.
 */
class NpxKtAgentIntegrationTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude connects through npx kt stdio proxy`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime,
            "ide-agent",
            consoleTitle = "npx-kt-claude",
            aiMode = AiMode.AI_NPX,
        ).waitForProjectReady()

        val prompt = buildString {
            appendLine("# Task: verify npx-kt MCP transport")
            appendLine()
            appendLine("Use the MCP Steroid tools from the mcp-steroid MCP server. Do not inspect files directly.")
            appendLine("1. Call steroid_list_projects and count the projects.")
            appendLine("2. Choose the current project_name from that response.")
            appendLine("3. Call steroid_fetch_resource with that project_name and URI ${McpResourceUris.promptSkill}.")
            appendLine("4. Reply with exactly these marker lines:")
            appendLine("NPX_KT_PROJECTS: <number>")
            appendLine("NPX_KT_RESOURCE_READ: yes")
            appendLine("NPX_KT_TRANSPORT: stdio")
        }

        val result = session.aiAgents.claude.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        val hasTransportMarker = hasMarker(output, "NPX_KT_TRANSPORT", "stdio")
        if (result.exitCode != 0 && !hasTransportMarker) {
            result.assertExitCode(0) { "Claude npx-kt transport test failed before markers" }
        }

        check(combined.contains("steroid_list_projects")) {
            "Agent must call steroid_list_projects through the npx-kt MCP server. Output:\n${combined.take(4000)}"
        }
        check(combined.contains("steroid_fetch_resource")) {
            "Agent must call steroid_fetch_resource through the npx-kt MCP server. Output:\n${combined.take(4000)}"
        }
        check(hasMarker(output, "NPX_KT_RESOURCE_READ", "yes")) {
            "Missing NPX_KT_RESOURCE_READ marker. Output:\n${output.take(4000)}"
        }
        check(hasTransportMarker) {
            "Missing NPX_KT_TRANSPORT marker. Output:\n${output.take(4000)}"
        }

        val ideaLog = File(session.runDirInContainer, "intellij/ide-log/idea.log")
        val ideaLogText = ideaLog.readText()
        check(ideaLogText.contains("[MCP-BUILTIN-RPC] <- POST /api/mcp-steroid/v1/tools/call/stream")) {
            "npx-kt must dispatch IDE tool calls through the built-in-webserver RPC bridge. " +
                "Missing built-in stream call in ${ideaLog.absolutePath}"
        }
        val retiredBridgePath = listOf("", "npx", "v1", "tools", "call", "stream").joinToString("/")
        check(!ideaLogText.contains("[MCP-HTTP] <- POST $retiredBridgePath")) {
            "npx-kt must not fall back to the legacy Ktor bridge for tool calls once the marker advertises built-in RPC. " +
                "Unexpected legacy stream call in ${ideaLog.absolutePath}"
        }
    }

    private fun hasMarker(output: String, marker: String, expectedValue: String): Boolean {
        val expected = "$marker: $expectedValue"
        return output.lineSequence().any { line ->
            line.contains(expected, ignoreCase = true)
        }
    }
}
