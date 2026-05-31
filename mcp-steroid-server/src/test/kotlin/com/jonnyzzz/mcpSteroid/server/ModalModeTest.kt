/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModalModeTest {
    @Test
    fun `fromWire maps each wire value`() {
        assertEquals(ModalMode.SMART_NON_MODAL, ModalMode.fromWire("smart_non_modal"))
        assertEquals(ModalMode.NON_MODAL, ModalMode.fromWire("non_modal"))
        assertEquals(ModalMode.UNLEASHED, ModalMode.fromWire("unleashed"))
    }

    @Test
    fun `fromWire null returns the default smart_non_modal`() {
        assertEquals(ModalMode.DEFAULT, ModalMode.fromWire(null))
        assertEquals(ModalMode.SMART_NON_MODAL, ModalMode.DEFAULT)
    }

    @Test
    fun `fromWire rejects an unknown value with a tool error listing the valid ones`() {
        val e = assertThrows(ToolCallErrorException::class.java) { ModalMode.fromWire("turbo") }
        val msg = e.message ?: ""
        assertTrue(msg.contains("smart_non_modal") && msg.contains("non_modal") && msg.contains("unleashed"),
            "error should list the valid modes, got: $msg")
    }

    @Test
    fun `wire round-trips for every enum value`() {
        ModalMode.entries.forEach { mode ->
            assertEquals(mode, ModalMode.fromWire(mode.wire), "wire round-trip for $mode")
        }
    }
}
