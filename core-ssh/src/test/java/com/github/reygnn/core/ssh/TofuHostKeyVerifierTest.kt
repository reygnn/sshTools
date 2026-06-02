package com.github.reygnn.core.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure JVM — mints real Ed25519 keys through a local BouncyCastle provider (no
 * global [java.security.Security] mutation, no Android runtime), so
 * [hostKeyFingerprint] runs the actual sshj serialisation path. Pins the
 * trust-on-first-use security contract of [TofuHostKeyVerifier].
 */
class TofuHostKeyVerifierTest {

    private val bc = BouncyCastleProvider()

    private fun ed25519Key(): PublicKey =
        KeyPairGenerator.getInstance("Ed25519", bc).generateKeyPair().public

    @Test
    fun `first use learns an OpenSSH SHA256 fingerprint and accepts`() {
        val key = ed25519Key()
        val learned = AtomicReference<String?>(null)
        val verifier = TofuHostKeyVerifier(expectedFingerprint = null) { learned.set(it) }

        assertTrue(verifier.verify("host", 22, key))
        assertEquals(hostKeyFingerprint(key), learned.get())
        assertTrue(learned.get()!!.startsWith("SHA256:"))
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
    fun `changed host key is rejected when a fingerprint is pinned and never learned`() {
        val pinned = hostKeyFingerprint(ed25519Key())
        val attacker = ed25519Key()
        val learned = AtomicReference<String?>(null)
        val verifier = TofuHostKeyVerifier(pinned) { learned.set(it) }

        assertFalse(verifier.verify("host", 22, attacker))
        assertNull(learned.get())
    }

    @Test
    fun `a syntactically wrong pinned fingerprint is rejected`() {
        val verifier = TofuHostKeyVerifier(expectedFingerprint = "SHA256:not-the-real-one")

        assertFalse(verifier.verify("host", 22, ed25519Key()))
    }
}
