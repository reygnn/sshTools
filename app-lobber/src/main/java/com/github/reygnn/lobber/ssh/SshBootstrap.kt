package com.github.reygnn.lobber.ssh

interface SshBootstrap {
    /**
     * Phase 1 of onboarding: connect WITHOUT authenticating, learn the host-key
     * fingerprint (OpenSSH `SHA256:…`), disconnect. No secret is transmitted —
     * the fingerprint is shown to the user for confirmation *before* the password
     * is ever sent, so a man-in-the-middle on first contact can't harvest it.
     * See AUDIT V4.
     */
    suspend fun discoverHostKey(host: String, port: Int): String

    /**
     * Phase 2: pin [expectedFingerprint] (the user-confirmed host key) *before*
     * authenticating, then send the password and append the public key. If the
     * host now presents a different key (MITM), the connect aborts before the
     * password leaves the device.
     */
    suspend fun pushPublicKey(
        host: String, port: Int, username: String,
        password: String, publicKeyLine: String, expectedFingerprint: String,
    )

    suspend fun verifyPubkeyAuth(config: SshConfig)
}
