package com.github.reygnn.core.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64

/**
 * Android ships a stripped-down "BC" provider that lacks the algorithms sshj
 * needs to load Ed25519 keys. Registering our own [BouncyCastleProvider] at
 * slot 1 ensures sshj — and any other JCE consumer — sees the full BC with
 * Ed25519 support.
 */
object SshSecurity {
    fun installBouncyCastle() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}

/** Wraps [s] in single quotes for safe use as one shell argument. */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Like [shellQuote] but leaves a leading `~/` unquoted so bash performs
 * tilde expansion. Single-quoted paths suppress tilde expansion entirely.
 */
fun pathQuote(path: String): String = when {
    path == "~" -> "~"
    path.startsWith("~/") -> "~/" + shellQuote(path.removePrefix("~/"))
    else -> shellQuote(path)
}

/**
 * OpenSSH-compatible SHA-256 fingerprint of an SSH public key, in
 * `SHA256:<base64-no-padding>` format — identical to `ssh-keygen -lf` output.
 */
fun hostKeyFingerprint(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/**
 * Trust-on-first-use host-key verifier.
 *
 * - First contact ([expectedFingerprint] == null): accepts the key and calls
 *   [onLearn] with its fingerprint so the caller can pin it.
 * - Subsequent connects: the presented key must match the pinned fingerprint
 *   exactly. A mismatch returns false → sshj aborts the connection.
 *
 * The comparison is constant-time ([MessageDigest.isEqual]).
 */
class TofuHostKeyVerifier(
    private val expectedFingerprint: String?,
    private val onLearn: (String) -> Unit = {},
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val actual = hostKeyFingerprint(key)
        val expected = expectedFingerprint
        if (expected == null) {
            onLearn(actual)
            return true
        }
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
