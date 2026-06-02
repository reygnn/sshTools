package com.github.reygnn.core.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.schmizz.sshj.common.KeyType
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Signature

/**
 * Instrumented (Tier 3): pins [BcOpenSshKeyProvider] against Android's **Conscrypt**.
 *
 * This is the one path the JVM integration test (Tier 1) cannot reproduce: on a
 * desktop JVM the JCE serves Ed25519 broadly, but on Android
 * `KeyFactory.getInstance("Ed25519")` lands on Conscrypt, which rejects sshj's
 * PKCS#8 preamble. [BcOpenSshKeyProvider] forces a concrete BouncyCastle instance
 * to dodge that. These tests only mean anything on a real device/emulator, where
 * Conscrypt is the system provider.
 *
 * Run: `./gradlew :core-ssh:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class BcOpenSshKeyProviderInstrumentedTest {

    @Before
    fun setUp() {
        SshSecurity.installBouncyCastle()
    }

    @Test
    fun loads_a_generated_ed25519_key_with_conscrypt_present() {
        val pair = SshKeygen.generateEd25519()
        val provider = BcOpenSshKeyProvider(pair.privateKeyPem)
        assertNotNull(provider.private)
        assertNotNull(provider.public)
        assertEquals(KeyType.ED25519, provider.type)
    }

    @Test
    fun the_loaded_keypair_signs_and_verifies() {
        val pair = SshKeygen.generateEd25519()
        val provider = BcOpenSshKeyProvider(pair.privateKeyPem)
        val message = "the quick brown fox".toByteArray()

        val signer = Signature.getInstance("Ed25519", BouncyCastleProvider())
        signer.initSign(provider.private)
        signer.update(message)
        val signature = signer.sign()

        val verifier = Signature.getInstance("Ed25519", BouncyCastleProvider())
        verifier.initVerify(provider.public)
        verifier.update(message)
        assertTrue("signature from the loaded private key must verify", verifier.verify(signature))
    }

    @Test
    fun the_loaded_public_key_fingerprints_as_openssh_sha256() {
        val pair = SshKeygen.generateEd25519()
        val provider = BcOpenSshKeyProvider(pair.privateKeyPem)
        // Exercises the sshj public-key serialisation on-device too.
        assertTrue(hostKeyFingerprint(provider.public).startsWith("SHA256:"))
    }
}
