/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.websitegen

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class PgpVerifierTest {
    private val data = "the JDK archive bytes".encodeToByteArray()

    @Test
    fun `accepts a valid detached signature`() {
        val keys = TestPgp.generate()
        PgpVerifier.verifyDetached(data, keys.signDetached(data), keys.publicKeyRing)
    }

    @Test
    fun `rejects when the signed data was tampered with`() {
        val keys = TestPgp.generate()
        val signature = keys.signDetached(data)
        val tampered = data.copyOf().also { it[0] = (it[0] + 1).toByte() }

        val ex = assertFailsWith<IllegalStateException> {
            PgpVerifier.verifyDetached(tampered, signature, keys.publicKeyRing)
        }
        assert(ex.message!!.contains("FAILED")) { ex.message!! }
    }

    @Test
    fun `rejects a signature made by a key not in the key set`() {
        val signer = TestPgp.generate()
        val other = TestPgp.generate()

        // Signature is valid, but verified against the WRONG public key set -> key not found.
        assertFailsWith<IllegalStateException> {
            PgpVerifier.verifyDetached(data, signer.signDetached(data), other.publicKeyRing)
        }
    }
}
