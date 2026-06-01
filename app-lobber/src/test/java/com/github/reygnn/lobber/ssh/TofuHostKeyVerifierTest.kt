package com.github.reygnn.lobber.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.PublicKey

/**
 * Pure JVM — uses a freshly generated Ed25519 key as the stand-in "host key".
 * Pins down the trust-on-first-use logic without a real SSH handshake.
 */
class TofuHostKeyVerifierTest {

    private fun hostKey(): PublicKey =
        BcOpenSshKeyProvider(SshKeygen.generateEd25519().privateKeyPem).public

    @Test
    fun `first use learns an OpenSSH SHA256 fingerprint and accepts`() {
        val key = hostKey()
        var learned: String? = null
        val verifier = TofuHostKeyVerifier(expectedFingerprint = null) { learned = it }

        assertTrue(verifier.verify("host", 22, key))
        assertNotNull(learned)
        assertTrue(learned!!.startsWith("SHA256:"))
    }

    @Test
    fun `a matching pinned fingerprint is accepted`() {
        val key = hostKey()
        var learned: String? = null
        TofuHostKeyVerifier(expectedFingerprint = null) { learned = it }.verify("host", 22, key)

        assertTrue(TofuHostKeyVerifier(learned).verify("host", 22, key))
    }

    @Test
    fun `a mismatched pinned fingerprint is rejected`() {
        val verifier = TofuHostKeyVerifier(expectedFingerprint = "SHA256:not-the-real-one")

        assertFalse(verifier.verify("host", 22, hostKey()))
    }

    @Test
    fun `learn is never invoked once a fingerprint is pinned`() {
        var learnCalled = false
        TofuHostKeyVerifier(expectedFingerprint = "SHA256:pinned") { learnCalled = true }
            .verify("host", 22, hostKey())

        assertFalse(learnCalled)
    }
}
