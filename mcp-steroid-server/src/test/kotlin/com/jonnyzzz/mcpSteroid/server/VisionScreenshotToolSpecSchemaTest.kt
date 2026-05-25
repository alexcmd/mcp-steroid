/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class VisionScreenshotToolSpecSchemaTest {
    @Test
    fun `inputSchema is valid JSON Schema`() {
        assertToolSpecHasValidJsonSchema(VisionScreenshotToolSpec { unreachableHandler() })
    }
}
