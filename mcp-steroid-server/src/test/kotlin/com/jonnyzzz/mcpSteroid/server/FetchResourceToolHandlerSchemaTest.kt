/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

/**
 * `FetchResourceToolHandler` is the dual-role tool: it is itself an
 * `McpTool` (registered in `McpSteroidTools.registerAll` alongside the
 * `*ToolSpec` siblings) and the consumer of `PromptsContextHandler`.
 * The schema test is identical in shape to the rest of the suite.
 */
class FetchResourceToolHandlerSchemaTest {
    @Test
    fun `inputSchema is valid JSON Schema`() {
        assertToolSpecHasValidJsonSchema(FetchResourceToolHandler { unreachableHandler() })
    }
}
