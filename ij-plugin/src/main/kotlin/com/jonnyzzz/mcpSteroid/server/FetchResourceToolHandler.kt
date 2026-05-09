/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpResourceReader
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.FindDuplicatesPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.InspectAndFixPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.DebuggerSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.TestSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJPromptArticle
import kotlinx.serialization.json.*

/**
 * Simple tool that fetches any MCP Steroid resource by URI and returns its markdown content.
 * Agents can call this instead of ReadMcpResourceTool — it's a purpose-built MCP tool
 * visible in the tool list, making resource discovery more natural.
 */
class FetchResourceToolHandler(
    private val resources: McpResourceReader,
) : McpTool {

    private val log = thisLogger()

    override val name = "steroid_fetch_resource"

    override val description: String get() {
        val testSkillUri = TestSkillPromptArticle().uri
        val debuggerUri = DebuggerSkillPromptArticle().uri
        val skillUri = SkillPromptArticle().uri
        val codingGuideUri = CodingWithIntelliJPromptArticle().uri
        val findDuplicatesUri = FindDuplicatesPromptArticle().uri
        val inspectAndFixUri = InspectAndFixPromptArticle().uri
        return "Fetch a mcp-steroid:// skill guide by URI. Returns markdown with copy-paste Kotlin code recipes for steroid_execute_code. " +
                "Running tests? → $testSkillUri | " +
                "Debugging? → $debuggerUri | " +
                "Find duplicates / clones / copy-pasted code / DRY violations? → $findDuplicatesUri | " +
                "Run a named inspection + quick fix? → $inspectAndFixUri | " +
                "Any IDE task? → $skillUri | " +
                "Full reference? → $codingGuideUri"
    }

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("uri") {
                put("type", "string")
                put("description", "The resource URI to fetch (from ListMcpResourcesTool or MCP server instructions)")
            }
        }
        putJsonArray("required") { add("uri") }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val uri = context.params.arguments["uri"]?.jsonPrimitive?.content
            ?: return ToolCallResult(
                content = listOf(ContentItem.Text(text = "ERROR: Missing required parameter: uri")),
                isError = true
            )

        log.info("steroid_fetch_resource: $uri")

        val result = resources.readResource(uri)
            ?: return ToolCallResult(
                content = listOf(ContentItem.Text(text = "ERROR: Resource not found: $uri")),
                isError = true
            )

        val text = result.contents.firstOrNull()?.text
            ?: return ToolCallResult(
                content = listOf(ContentItem.Text(text = "ERROR: Resource empty: $uri")),
                isError = true
            )

        return ToolCallResult(content = listOf(ContentItem.Text(text = text)))
    }
}
