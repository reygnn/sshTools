package com.github.reygnn.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Instrumented (Tier 3): exercises [KeyVault] against the **real** hardware-backed
 * Android Keystore — the one thing Robolectric cannot provide. This is the only
 * test that actually verifies the at-rest encryption promise end-to-end (AUDIT
 * V8/V11): a non-exportable AES-256-GCM key, a fresh IV per call, and GCM tag
 * authentication that rejects a tampered blob.
 *
 * Run on a connected device/emulator: `./gradlew :core-data:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class KeyVaultInstrumentedTest {

    private val pem =
        "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAA\n-----END OPENSSH PRIVATE KEY-----"

    @Test
    fun encrypt_then_decrypt_roundtrips() {
        val blob = KeyVault.encrypt(pem)
        assertEquals(pem, KeyVault.decrypt(blob))
    }

    @Test
    fun encrypt_uses_a_fresh_iv_per_call() {
        val a = KeyVault.encrypt("same plaintext")
        val b = KeyVault.encrypt("same plaintext")
        assertFalse("ciphertext (IV || data) must differ per call", a.contentEquals(b))
        // Both still decrypt back to the same plaintext.
        assertEquals("same plaintext", KeyVault.decrypt(a))
        assertEquals("same plaintext", KeyVault.decrypt(b))
    }

    @Test(expected = GeneralSecurityException::class)
    fun a_tampered_blob_fails_the_gcm_tag() {
        val blob = KeyVault.encrypt("secret")
        // Flip a bit in the trailing GCM tag — decrypt must reject (AEADBadTagException).
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        KeyVault.decrypt(blob)
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_blob_shorter_than_the_iv_is_rejected() {
        KeyVault.decrypt(ByteArray(8)) // < 12-byte IV
    }

    @Test
    fun on_disk_format_roundtrips_through_decodeKeyBlob() {
        // The real on-disk path: encrypt -> Base64 -> decodeKeyBlob -> Keystore decrypt.
        val base64 = Base64.getEncoder().encodeToString(KeyVault.encrypt(pem))
        val decoded = decodeKeyBlob(base64, decrypt = { KeyVault.decrypt(it) })
        assertEquals(pem, decoded)
    }
}
