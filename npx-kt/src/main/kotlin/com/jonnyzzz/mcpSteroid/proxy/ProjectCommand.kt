/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import java.io.PrintStream

internal fun runProjectCommand(
    out: PrintStream,
    json: Boolean = false,
) {
    if (json) {
        out.println("{}")
    } else {
        out.println("project: not yet implemented")
    }
}
