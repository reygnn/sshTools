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
 * sshj [KeyProvider] für OpenSSH-Format Ed25519-Privates Keys, der sshj's
 * eigenen Parsing-Pfad komplett umgeht.
 *
 * Hintergrund: sshj's Ed25519KeyFactory baut PKCS#8-Bytes mit hardcoded Preamble
 * und ruft `KeyFactory.getInstance("Ed25519")`. Auf Android landet das oft auf
 * Conscrypt — dem System-Provider, der die Preamble nicht akzeptiert. Auch ein
 * vorab registriertes BouncyCastle hilft nicht zuverlässig.
 *
 * Hier umgehen wir den Pfad: [KeyFactory.getInstance] wird mit einer konkreten
 * BouncyCastleProvider-Instanz aufgerufen (garantiert BC), und
 * [OpenSSHPrivateKeySpec] / [OpenSSHPublicKeySpec] lassen BC den Blob direkt
 * parsen — kein PKCS#8-Umweg, kein Preamble-Mismatch.
 */
class BcOpenSshKeyProvider(privateKeyPem: String) : KeyProvider {

    private val privateKey: PrivateKey
    private val publicKey: PublicKey

    init {
        val openSshBlob = decodePemBody(privateKeyPem)
        val bcParam = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(openSshBlob)
        require(bcParam is Ed25519PrivateKeyParameters) {
            "Erwartet Ed25519, ist aber ${bcParam::class.simpleName}"
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
