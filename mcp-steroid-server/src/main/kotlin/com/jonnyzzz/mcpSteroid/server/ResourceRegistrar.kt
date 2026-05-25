/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpPromptRegistrar
import com.jonnyzzz.mcpSteroid.mcp.Prompt
import com.jonnyzzz.mcpSteroid.mcp.PromptContent
import com.jonnyzzz.mcpSteroid.mcp.PromptGetResult
import com.jonnyzzz.mcpSteroid.mcp.PromptMessage
import com.jonnyzzz.mcpSteroid.prompts.PromptIndexBase
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex

/**
 * Registers generated prompt articles as MCP **prompts** for the `prompt/`
 * folder. Articles are intentionally NOT registered as MCP `resources/` —
 * the dedicated [FetchResourceToolHandler] tool (`steroid_fetch_resource`)
 * is the single discovery surface. The tool requires `project_name` so
 * rendering picks up the project's [PromptsContext] (IDE conditionals,
 * fence filters), which `resources/read` cannot supply.
 *
 * Articles are filtered by their root `IdeFilter` unless context rendering is deferred.
 *
 * Content is rendered via `ArticleBase.readPayload` which handles per-part
 * filtering and see-also filtering internally.
 */
class ResourceRegistrar(
    private val deferContext: Boolean = false,
    private val handler: () -> PromptsContextHandler,
) {

    companion object {
        /** Bare `mcp-steroid://` scheme prefix — anything starting with it is one of our resources. */
        const val ROOT_RESOURCE_URI: String = "mcp-steroid://"
    }

    fun register(prompts: McpPromptRegistrar) {
        val resourcesIndex = ResourcesIndex()
        val context = handler().buildPromptsContext()

        for ((folder, index) in resourcesIndex.roots) {
            if (folder == "prompt") {
                registerSkillPrompts(prompts, index, context)
            }
        }
    }

    private fun registerSkillPrompts(
        prompts: McpPromptRegistrar,
        index: PromptIndexBase,
        context: PromptsContext,
    ) {
        for ((_, article) in index.articles) {
            if (!deferContext && !article.filter.matches(context)) continue

            prompts.registerPrompt(
                Prompt(
                    name = article.uri,
                    title = article.title.readPrompt(),
                    description = article.description.readPrompt(),
                )
            ) {
                PromptGetResult(
                    description = article.description.readPrompt(),
                    messages = listOf(
                        PromptMessage(
                            role = "user",
                            content = PromptContent.Text(article.readPayload(renderContext(context)))
                        )
                    )
                )
            }
        }
    }

    private fun renderContext(registrationContext: PromptsContext): PromptsContext =
        if (deferContext) handler().buildPromptsContext() else registrationContext
}
