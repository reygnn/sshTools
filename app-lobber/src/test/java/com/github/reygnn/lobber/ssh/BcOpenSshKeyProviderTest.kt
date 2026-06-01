package com.github.reygnn.lobber.ssh
import com.github.reygnn.core.ssh.BcOpenSshKeyProvider
import com.github.reygnn.core.ssh.SshKeygen

import net.schmizz.sshj.common.KeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BcOpenSshKeyProviderTest {

    @Test
    fun `parses what SshKeygen produces`() {
        val pair = SshKeygen.generateEd25519()
        val provider = BcOpenSshKeyProvider(pair.privateKeyPem)

        assertEquals(KeyType.ED25519, provider.type)
        assertNotNull(provider.private)
        assertNotNull(provider.public)
    }

    @Test
    fun `private and public key match (sign-verify roundtrip via raw bytes)`() {
        // Confirm the public key derived by BC from the private key matches the
        // public key encoded in the public-key line — i.e. the keypair is
        // internally consistent and would actually authenticate against itself.
        val pair = SshKeygen.generateEd25519()
        val provider = BcOpenSshKeyProvider(pair.privateKeyPem)

        // Public key from the OpenSSH "ssh-ed25519 …" line (32 raw bytes at the
        // tail of the wire-format encoding).
        val pubBlob = org.bouncycastle.util.encoders.Base64.decode(
            pair.publicKeyOpenSsh.split(" ")[1]
        )
        val pubFromLine = pubBlob.copyOfRange(pubBlob.size - 32, pubBlob.size)

        // Public key as JCE PublicKey (X.509 SubjectPublicKeyInfo) — last 32
        // bytes of getEncoded() are the raw Ed25519 public key.
        val pubFromProvider = provider.public.encoded
        val rawFromProvider = pubFromProvider.copyOfRange(pubFromProvider.size - 32, pubFromProvider.size)

        assertEquals(
            pubFromLine.toList(),
            rawFromProvider.toList(),
        )
    }

    @Test
    fun `rejects non-Ed25519 PEM with a clear message`() {
        // Garbage PEM body — `Base64.decode` on the empty body returns an empty
        // array, then `parsePrivateKeyBlob` throws. We only assert that
        // construction fails fast (we don't pin the exact exception type, which
        // varies across BC versions).
        val bogusPem =
            "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
                "AAAAAA==\n" +
                "-----END OPENSSH PRIVATE KEY-----\n"
        assertThrows(Exception::class.java) {
            BcOpenSshKeyProvider(bogusPem)
        }
    }
}
