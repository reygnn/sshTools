package com.github.reygnn.caster.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.github.reygnn.core.ssh.TofuHostKeyVerifier
import com.github.reygnn.core.ssh.hostKeyFingerprint
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure JVM — uses BouncyCastle (already a dependency) to mint a real Ed25519
 * key, no Android runtime. Pins the trust-on-first-use security contract.
 */
class TofuHostKeyVerifierTest {

    private val bc = BouncyCastleProvider()

    private fun ed25519Key(): PublicKey =
        KeyPairGenerator.getInstance("Ed25519", bc).generateKeyPair().public

    @Test
    fun `unknown host is learned and accepted on first use`() {
        val key = ed25519Key()
        val learned = AtomicReference<String?>(null)
        val verifier = TofuHostKeyVerifier(expectedFingerprint = null) { learned.set(it) }

        assertTrue(verifier.verify("host", 22, key))
        assertEquals(hostKeyFingerprint(key), learned.get())
    }

    @Test
    fun `matching pinned fingerprint is accepted and nothing re-learned`() {
        val key = ed25519Key()
        val learned = AtomicReference<String?>(null)
        val verifier = TofuHostKeyVerifier(hostKeyFingerprint(key)) { learned.set(it) }

        assertTrue(verifier.verify("host", 22, key))
        assertNull(learned.get())
    }

    @Test
    fun `changed host key is rejected when a fingerprint is pinned`() {
        val pinned = hostKeyFingerprint(ed25519Key())
        val attacker = ed25519Key()
        val verifier = TofuHostKeyVerifier(pinned)

        assertFalse(verifier.verify("host", 22, attacker))
    }
}
