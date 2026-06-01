package com.github.reygnn.lobber.ssh
import com.github.reygnn.core.ssh.SshSecurity

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.KeyFactory
import java.security.Security

class SshSecurityTest {

    @Test
    fun `installBouncyCastle puts our BC at slot 1`() {
        SshSecurity.installBouncyCastle()
        val bc = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        assertNotNull("BC provider must be registered", bc)
        assertEquals(BouncyCastleProvider::class.java, bc.javaClass)
        assertEquals("BC must be the highest-priority provider", bc, Security.getProviders()[0])
    }

    @Test
    fun `Ed25519 KeyFactory resolves through BC after install`() {
        SshSecurity.installBouncyCastle()
        // Throws NoSuchAlgorithmException if the registered "BC" lacks Ed25519 —
        // exactly the symptom we saw on Android with the stripped system BC.
        val factory = KeyFactory.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        assertNotNull(factory)
    }

    @Test
    fun `installBouncyCastle is idempotent`() {
        SshSecurity.installBouncyCastle()
        SshSecurity.installBouncyCastle()
        val matches = Security.getProviders().count { it.name == BouncyCastleProvider.PROVIDER_NAME }
        assertEquals(1, matches)
    }
}
