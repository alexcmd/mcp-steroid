/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test for IdeContainerSession infrastructure.
 *
 * Verifies that the Docker container can be built and started,
 * all directories are properly mounted, and the IDE starts successfully.
 */
class IntelliJContainerTest {
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `container starts and IDE becomes ready`() = runWithCloseableStack { lifetime ->
        IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "ide-container",
        ))
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `xdotool input control works`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "ide-container-input"
        ))

        // Move the mouse to the center of the screen
        session.input.mouseMove(1920, 1080)

        // Click at the center
        session.input.mouseClick(1920, 1080)

        // Type some text (will go to whatever is focused)
        session.input.typeText("hello from xdotool")

        // Press Escape to dismiss any popup
        session.input.keyPress("Escape")

        // Verify we can query window info without crashing
        val activeWindow = session.input.getActiveWindowId()
        println("[test] Active window ID: $activeWindow")

        // Verify clipboard round-trip
        session.input.clipboardCopy("mcp-steroid-test")
        val pasted = session.input.clipboardPaste()
        check(pasted.contains("mcp-steroid-test")) {
            "Clipboard round-trip failed: expected 'mcp-steroid-test', got '$pasted'"
        }
    }
}
