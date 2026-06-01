package com.github.reygnn.prodder.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import com.github.reygnn.core.ssh.TofuHostKeyVerifier
import com.github.reygnn.core.ssh.hostKeyFingerprint
import com.github.reygnn.core.ssh.SshSecurity
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * Pure JVM. Generates real Ed25519 host keys through BouncyCastle (the same
 * provider the app registers) so [hostKeyFingerprint] runs the actual sshj
 * serialisation path — no mocking of the crypto.
 */
class PinningHostKeyVerifierTest {

    companion object {
        private lateinit var keyA: KeyPair
        private lateinit var keyB: KeyPair

        @BeforeClass
        @JvmStatic
        fun generateKeys() {
            SshSecurity.installBouncyCastle()
            val gen = KeyPairGenerator.getInstance("Ed25519", "BC")
            keyA = gen.generateKeyPair()
            keyB = gen.generateKeyPair()
        }
    }

    @Test
    fun `unpinned verifier learns the fingerprint and accepts (TOFU)`() {
        var learned: String? = null
        val verifier = TofuHostKeyVerifier(expectedFingerprint = null) { learned = it }

        assertTrue(verifier.verify("host", 22, keyA.public))
        assertEquals(hostKeyFingerprint(keyA.public), learned)
    }

    @Test
    fun `pinned verifier accepts the matching key without re-learning`() {
        val verifier = TofuHostKeyVerifier(expectedFingerprint = hostKeyFingerprint(keyA.public)) {
            fail("a matching key must not be re-learned")
        }

        assertTrue(verifier.verify("host", 22, keyA.public))
    }

    @Test
    fun `pinned verifier rejects a different key and never learns it`() {
        var learned: String? = null
        val verifier = TofuHostKeyVerifier(expectedFingerprint = hostKeyFingerprint(keyA.public)) {
            learned = it
        }

        assertFalse(verifier.verify("host", 22, keyB.public))
        assertNull(learned)
    }

    @Test
    fun `fingerprint uses the OpenSSH SHA256 form`() {
        assertTrue(hostKeyFingerprint(keyA.public).startsWith("SHA256:"))
    }
}
