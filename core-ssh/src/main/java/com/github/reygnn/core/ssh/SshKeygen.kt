package com.github.reygnn.core.ssh

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.util.encoders.Base64
import java.security.SecureRandom

data class SshKeyPair(
    /** OpenSSH-PEM-Block, kompatibel zu sshj. */
    val privateKeyPem: String,
    /** Eine Zeile `ssh-ed25519 <base64> <comment>`, anhängbar an `authorized_keys`. */
    val publicKeyOpenSsh: String,
)

object SshKeygen {
    fun generateEd25519(comment: String = "ssh-tools@android"): SshKeyPair {
        val generator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }
        val pair = generator.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val pub  = pair.public  as Ed25519PublicKeyParameters

        val privBlob = OpenSSHPrivateKeyUtil.encodePrivateKey(priv)
        val pubBlob  = OpenSSHPublicKeyUtil.encodePublicKey(pub)

        return SshKeyPair(
            privateKeyPem    = wrapPem(privBlob),
            publicKeyOpenSsh = "ssh-ed25519 ${Base64.toBase64String(pubBlob)} $comment",
        )
    }

    private fun wrapPem(blob: ByteArray): String = buildString {
        append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
        Base64.toBase64String(blob).chunked(70).forEach { append(it).append('\n') }
        append("-----END OPENSSH PRIVATE KEY-----\n")
    }
}
