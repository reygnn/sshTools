package com.github.reygnn.lobber.ssh

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class SshKeygenTest {

    @Test
    fun `private key is wrapped in OpenSSH PEM markers`() {
        val pair = SshKeygen.generateEd25519()
        assertTrue(pair.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(pair.privateKeyPem.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----"))
    }

    @Test
    fun `private key roundtrips through BouncyCastle parser`() {
        val pair = SshKeygen.generateEd25519()
        val parsed = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(decodePem(pair.privateKeyPem))
        assertTrue(parsed is Ed25519PrivateKeyParameters)
    }

    @Test
    fun `public key is single-line ssh-ed25519 format with comment`() {
        val pair = SshKeygen.generateEd25519(comment = "test@host")
        assertEquals(1, pair.publicKeyOpenSsh.lineSequence().count())

        val parts = pair.publicKeyOpenSsh.split(" ")
        assertEquals(3, parts.size)
        assertEquals("ssh-ed25519", parts[0])
        assertEquals("test@host", parts[2])
        Base64.decode(parts[1])
    }

    @Test
    fun `public key bytes match the embedded key in the private blob`() {
        val pair = SshKeygen.generateEd25519()
        val parsed = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(
            decodePem(pair.privateKeyPem),
        ) as Ed25519PrivateKeyParameters
        val expectedPub = parsed.generatePublicKey().encoded

        val pubBlob = Base64.decode(pair.publicKeyOpenSsh.split(" ")[1])
        // OpenSSH wire format: 4-byte length + "ssh-ed25519" + 4-byte length + 32 raw pubkey bytes.
        val rawPub = pubBlob.copyOfRange(pubBlob.size - 32, pubBlob.size)
        assertTrue(expectedPub.contentEquals(rawPub))
    }

    @Test
    fun `private key roundtrips through sshj's OpenSSHKeyV1 parser`() {
        val pair = SshKeygen.generateEd25519()
        val keyFile = OpenSSHKeyV1KeyFile()
        keyFile.init(StringReader(pair.privateKeyPem))
        val priv = keyFile.private
        val pub = keyFile.public
        assertNotNull("sshj must produce a private key", priv)
        assertNotNull("sshj must produce a public key", pub)
        assertEquals("Ed25519", pub.algorithm)
    }

    @Test
    fun `two consecutive generations yield different keys`() {
        val a = SshKeygen.generateEd25519()
        val b = SshKeygen.generateEd25519()
        assertNotEquals(a.privateKeyPem, b.privateKeyPem)
        assertNotEquals(a.publicKeyOpenSsh, b.publicKeyOpenSsh)
    }

    private fun decodePem(pem: String): ByteArray {
        val body = pem.lineSequence()
            .filterNot { it.startsWith("-----") || it.isBlank() }
            .joinToString("")
        return Base64.decode(body)
    }
}
