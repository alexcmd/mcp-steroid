/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class NpxKtAgentRoutingIntegrationTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun claudeUsesDevrigStdioToDiscoverProjectAndExecuteCode() {
        val agent = session.aiAgents.claude
        val diagnostics = session.diagnosticsSummary()
        val result = agent.runPrompt(
            prompt = """
                You are validating the MCP server named "mcp-steroid".

                Use MCP tools only for these steps:
                1. Call steroid_list_projects.
                2. Pick the single returned project_name exactly as returned.
                3. Call steroid_execute_code for that project_name with this Kotlin code:
                   println("DEVRIG_NPX_EXEC_OK")

                Print these final markers on separate lines:
                PROJECT_NAME: <the exact project_name you used>
                EXEC_RESULT: DEVRIG_NPX_EXEC_OK

                If any MCP call fails, print DEVRIG_NPX_FAILED and explain the failure.
                Output plain text only.
            """.trimIndent(),
            timeoutSeconds = 600,
        ).awaitForProcessFinish()
            .assertExitCode(0) {
                "[${agent.displayName}] devrig stdio MCP prompt failed with exit $exitCode: $stderr\n$diagnostics"
            }

        val combined = result.stdout + "\n" + result.stderr
        check(!combined.contains("DEVRIG_NPX_FAILED", ignoreCase = true)) {
            "agent reported DEVRIG_NPX_FAILED.\n$diagnostics\n$combined"
        }
        result.assertNoErrorsInOutput("devrig stdio MCP prompt must have no errors\n$diagnostics")

        result.assertOutputContains(
            "PROJECT_NAME:",
            message = "agent must report the routed project_name\n$diagnostics",
        )
        result.assertOutputContains(
            "EXEC_RESULT: DEVRIG_NPX_EXEC_OK",
            message = "agent must report execute_code marker\n$diagnostics",
        )

        check(combined.contains("steroid_list_projects") || combined.contains("list_projects")) {
            "agent output/logs should show project discovery through MCP.\n$diagnostics\n$combined"
        }
        check(combined.contains("steroid_execute_code") || combined.contains("execute_code")) {
            "agent output/logs should show execution through MCP.\n$diagnostics\n$combined"
        }
    }

    companion object {
        private val lifetime by lazy { CloseableStackHost(NpxKtAgentRoutingIntegrationTest::class.java.simpleName) }
        private val session by lazy {
            IntelliJContainer.create(
                lifetime = lifetime,
                dockerFileBase = "ide-agent",
                consoleTitle = "devrig stdio MCP agent routing",
                aiMode = AiMode.AI_NPX,
            ).waitForProjectReady()
        }

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            session.toString()
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }
}
