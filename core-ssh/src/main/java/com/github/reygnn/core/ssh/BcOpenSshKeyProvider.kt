package com.github.reygnn.core.ssh

import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.jcajce.spec.OpenSSHPrivateKeySpec
import org.bouncycastle.jcajce.spec.OpenSSHPublicKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.encoders.Base64
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey

/**
 * sshj [KeyProvider] for OpenSSH-format Ed25519 private keys that bypasses
 * sshj's own parsing path entirely.
 *
 * Background: sshj's Ed25519KeyFactory builds PKCS#8 bytes with a hardcoded preamble
 * and calls `KeyFactory.getInstance("Ed25519")`. On Android this often lands on
 * Conscrypt — the system provider, which does not accept the preamble. Even a
 * pre-registered BouncyCastle does not help reliably.
 *
 * Here we bypass that path: [KeyFactory.getInstance] is called with a concrete
 * BouncyCastleProvider instance (guaranteed BC), and
 * [OpenSSHPrivateKeySpec] / [OpenSSHPublicKeySpec] let BC parse the blob
 * directly — no PKCS#8 detour, no preamble mismatch.
 */
class BcOpenSshKeyProvider(privateKeyPem: String) : KeyProvider {

    private val privateKey: PrivateKey
    private val publicKey: PublicKey

    init {
        val openSshBlob = decodePemBody(privateKeyPem)
        val bcParam = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(openSshBlob)
        require(bcParam is Ed25519PrivateKeyParameters) {
            "Expected Ed25519, but is ${bcParam::class.simpleName}"
        }
        val keyFactory = KeyFactory.getInstance("Ed25519", BouncyCastleProvider())
        privateKey = keyFactory.generatePrivate(OpenSSHPrivateKeySpec(openSshBlob))
        val pubBlob = OpenSSHPublicKeyUtil.encodePublicKey(bcParam.generatePublicKey())
        publicKey = keyFactory.generatePublic(OpenSSHPublicKeySpec(pubBlob))
    }

    override fun getPrivate(): PrivateKey = privateKey
    override fun getPublic(): PublicKey = publicKey
    override fun getType(): KeyType = KeyType.ED25519

    private fun decodePemBody(pem: String): ByteArray =
        Base64.decode(
            pem.lineSequence()
                .filterNot { it.startsWith("-----") || it.isBlank() }
                .joinToString("")
        )
}
