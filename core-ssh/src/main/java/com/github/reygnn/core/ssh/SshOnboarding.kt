package com.github.reygnn.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.io.IOException

/**
 * App-agnostic onboarding primitives: authorise this device's public key on a
 * build host over a one-time password login. Two-phase by design so the password
 * is only sent after the host key the user confirmed has been pinned (AUDIT V4).
 *
 * Deliberately a **core-ssh primitive** — it takes only host/port/credentials,
 * never an app's `SshConfig`/`SshClient` — so Hard Rule 1 stays intact while all
 * three apps share the exact same onboarding handshake.
 */
interface SshOnboarding {
    /**
     * Phase 1: connect far enough to complete the transport handshake (which runs
     * host-key verification) and learn the host-key fingerprint (`SHA256:…`). No
     * user authentication, so no secret is transmitted.
     */
    suspend fun discoverHostKey(host: String, port: Int): String

    /**
     * Phase 2: pin [expectedFingerprint] (the user-confirmed host key) *before*
     * authenticating, then send [password] and append [publicKeyLine] to the
     * host's `authorized_keys`. If the host now presents a different key (MITM),
     * the connect aborts before the password leaves the device.
     */
    suspend fun pushPublicKey(
        host: String, port: Int, username: String,
        password: String, publicKeyLine: String, expectedFingerprint: String,
    )

    /** Confirms the new key authenticates against the pinned host, then drops it. */
    suspend fun verifyPubkeyAuth(
        host: String, port: Int, username: String,
        privateKeyPem: String, knownHostFingerprint: String,
    )
}

class SshjOnboarding : SshOnboarding {

    override suspend fun discoverHostKey(host: String, port: Int): String = withContext(Dispatchers.IO) {
        // Connect only far enough to learn the host key — no user auth, no secret.
        val ssh = SSHClient()
        var learned: String? = null
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(expectedFingerprint = null) { learned = it })
        try {
            ssh.connect(host, port)
        } finally {
            ssh.disconnect()
        }
        learned ?: throw IOException("Could not determine host-key fingerprint")
    }

    override suspend fun pushPublicKey(
        host: String, port: Int, username: String,
        password: String, publicKeyLine: String, expectedFingerprint: String,
    ): Unit = withContext(Dispatchers.IO) {
        val ssh = SSHClient()
        // Pin the confirmed host key: a different key now (MITM) aborts connect()
        // before authPassword runs, so the password never leaves the device.
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(expectedFingerprint = expectedFingerprint))
        ssh.connect(host, port)
        try {
            ssh.authPassword(username, password)
            // Success is silent; stderr carries the diagnostic on failure.
            val result = ssh.runCommand(
                "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                    "printf '%s\\n' ${shellQuote(publicKeyLine)} >> ~/.ssh/authorized_keys && " +
                    "chmod 600 ~/.ssh/authorized_keys",
                maxOutputBytes = MAX_OUTPUT_BYTES,
            )
            if (result.exitStatus != 0) {
                throw IOException(
                    "Pubkey push failed (exit=${result.exitStatus}): " +
                        result.stderr.ifBlank { result.stdout }
                )
            }
        } finally {
            ssh.disconnect()
        }
    }

    override suspend fun verifyPubkeyAuth(
        host: String, port: Int, username: String,
        privateKeyPem: String, knownHostFingerprint: String,
    ): Unit = withContext(Dispatchers.IO) {
        // Just confirm the key authenticates against the pinned host, then drop it.
        connectWithKey(
            host = host,
            port = port,
            username = username,
            privateKeyPem = privateKeyPem,
            knownHostFingerprint = knownHostFingerprint,
        ).use { /* auth succeeded if we got here */ }
    }

    private companion object {
        /** The bootstrap command is silent on success; cap its diagnostics generously. */
        const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}
