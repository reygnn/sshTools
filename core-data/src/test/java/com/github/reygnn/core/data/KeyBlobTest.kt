package com.github.reygnn.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Pure-JVM coverage of the at-rest key-blob decision (AUDIT V11). The real AES
 * decrypt needs the Android Keystore, so it is injected; here we pin the
 * branching: absent, legacy plaintext, encrypted, and the decrypt/decode-failure
 * fallback (AUDIT V8).
 */
class KeyBlobTest {

    @Test
    fun `blank blob returns null and never decrypts`() {
        var decryptCalled = false
        assertNull(decodeKeyBlob("   \n ", decrypt = { decryptCalled = true; "x" }))
        assertFalse(decryptCalled)
    }

    @Test
    fun `legacy plaintext PEM is returned as-is without decrypting`() {
        var decryptCalled = false
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nbody\n-----END OPENSSH PRIVATE KEY-----"
        // Trailing whitespace is trimmed; the prefix marks it as plaintext.
        assertEquals(pem, decodeKeyBlob("$pem\n", decrypt = { decryptCalled = true; "x" }))
        assertFalse(decryptCalled)
    }

    @Test
    fun `base64 blob is decoded and passed to decrypt`() {
        val cipher = byteArrayOf(1, 2, 3, 4)
        val raw = Base64.getEncoder().encodeToString(cipher)
        val result = decodeKeyBlob(raw, decrypt = { bytes ->
            assertArrayEquals(cipher, bytes)
            "DECRYPTED-PEM"
        })
        assertEquals("DECRYPTED-PEM", result)
    }

    @Test
    fun `decrypt failure returns null and reports the error`() {
        var reported: Throwable? = null
        val raw = Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9))
        val result = decodeKeyBlob(
            raw,
            decrypt = { throw GeneralSecurityException("GCM tag mismatch") },
            onError = { reported = it },
        )
        assertNull(result)
        assertNotNull(reported)
    }

    @Test
    fun `malformed base64 returns null and reports the error`() {
        var reported: Throwable? = null
        val result = decodeKeyBlob(
            "not valid base64 !!!",
            decrypt = { "should not be reached" },
            onError = { reported = it },
        )
        assertNull(result)
        assertNotNull(reported)
    }
}
