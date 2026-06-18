/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.websitegen

import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import java.io.ByteArrayInputStream

/**
 * Verifies a detached OpenPGP signature against a public key, using BouncyCastle. This is Amazon
 * Corretto's vendor-natural validation: every Corretto binary on `corretto.aws` ships a `<file>.sig`
 * detached signature made with the Amazon Corretto release key
 * (`https://apt.corretto.aws/corretto.key`, RSA-4096, fingerprint `…A122542AB04F24E3`).
 *
 * Both inputs are fed through [PGPUtil.getDecoderStream], which transparently handles binary OR
 * ASCII-armored encodings — the published key is armored, the `.sig` files are binary.
 */
object PgpVerifier {
    /**
     * Verify that [signature] (a detached OpenPGP signature) is a valid signature over [data], made by
     * a key contained in [publicKey]. Throws [IllegalStateException] / [IllegalArgumentException] with a
     * descriptive message on any failure — there is no "soft" failure mode.
     */
    fun verifyDetached(data: ByteArray, signature: ByteArray, publicKey: ByteArray) {
        val keyRings = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(publicKey)),
            JcaKeyFingerprintCalculator(),
        )

        val pgpObjects = JcaPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(signature)))
        val signatures = pgpObjects.nextObject() as? PGPSignatureList
            ?: error("No OpenPGP signature packet found in the provided .sig data")
        require(!signatures.isEmpty) { "Empty OpenPGP signature list" }

        val sig = signatures[0]
        val key = keyRings.getPublicKey(sig.keyID)
            ?: error("Signature was made by key 0x${sig.keyID.toString(16)}, which is not in the provided key set")

        sig.init(JcaPGPContentVerifierBuilderProvider(), key)
        sig.update(data)
        check(sig.verify()) {
            "OpenPGP signature verification FAILED for key 0x${sig.keyID.toString(16)} " +
                    "(${key.userIDs.asSequence().firstOrNull() ?: "no uid"})"
        }
    }
}
