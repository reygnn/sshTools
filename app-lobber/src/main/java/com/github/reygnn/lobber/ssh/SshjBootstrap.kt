package com.github.reygnn.lobber.ssh

import com.github.reygnn.core.ssh.TofuHostKeyVerifier
import com.github.reygnn.core.ssh.connectWithKey
import com.github.reygnn.core.ssh.runCommand
import com.github.reygnn.core.ssh.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.io.IOException

class SshjBootstrap : SshBootstrap {

    override suspend fun discoverHostKey(host: String, port: Int): String = withContext(Dispatchers.IO) {
        // Connect only far enough to complete the transport handshake (which runs
        // host-key verification) — no user auth, so no secret is sent. See AUDIT V4.
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
        // Password auth (not pubkey), so the SSHClient is built here rather than
        // via connectWithKey; the command drain/join is shared (runCommand).
        val ssh = SSHClient()
        // Pin the user-confirmed host key: if the server now presents a different
        // key (MITM), connect() aborts before authPassword runs — the password
        // never leaves the device. See AUDIT V4.
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

    override suspend fun verifyPubkeyAuth(config: SshConfig) = withContext(Dispatchers.IO) {
        // Just confirm the key authenticates against the pinned host, then drop it.
        connectWithKey(
            host = config.host,
            port = config.port,
            username = config.username,
            privateKeyPem = config.privateKeyPem,
            knownHostFingerprint = config.knownHostFingerprint,
        ).use { /* auth succeeded if we got here */ }
    }

    private companion object {
        /** The bootstrap command is silent on success; cap its diagnostics generously. */
        const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}
