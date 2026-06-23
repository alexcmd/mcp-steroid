/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pins the `projectNameFor` hashing contract: deterministic, collision-free, 8-char base36, opaque. */
class ProjectNameTest {
    @Test
    fun `same project name yields the same id`() {
        val a = projectNameFor("mcp-steroid")
        val aAgain = projectNameFor("mcp-steroid")
        assertEquals(a, aAgain)
    }

    @Test
    fun `different project names yield different ids`() {
        val a = projectNameFor("alpha")
        val b = projectNameFor("bravo")
        assertNotEquals(a, b)
    }

    @Test
    fun `result is exactly 8 lowercase base36 chars`() {
        val id = projectNameFor("any-project")
        assertEquals(8, id.length, "projectNameFor must return exactly 8 characters; got '$id'")
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'z' },
            "projectNameFor must return lowercase base36 chars only; got '$id'")
    }

    @Test
    fun `result differs from the raw project name`() {
        // The hash is an opaque id — it must not accidentally equal the input name.
        val name = "mcp-steroid"
        assertNotEquals(name, projectNameFor(name))
    }
}
