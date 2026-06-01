package com.github.reygnn.lobber.ssh

import com.github.reygnn.core.ssh.BcOpenSshKeyProvider
import com.github.reygnn.core.ssh.TofuHostKeyVerifier
import com.github.reygnn.core.ssh.readCapped
import com.github.reygnn.core.ssh.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class SshjBootstrap : SshBootstrap {

    override suspend fun pushPublicKey(
        host: String, port: Int, username: String,
        password: String, publicKeyLine: String,
    ): String = withContext(Dispatchers.IO) {
        val ssh = SSHClient()
        var learned: String? = null
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(expectedFingerprint = null) { learned = it })
        ssh.connect(host, port)
        try {
            ssh.authPassword(username, password)
            ssh.startSession().use { session ->
                val cmd = session.exec(
                    "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                        "printf '%s\\n' ${shellQuote(publicKeyLine)} >> ~/.ssh/authorized_keys && " +
                        "chmod 600 ~/.ssh/authorized_keys"
                )
                // Drain stdout *and* stderr before join() so a full channel
                // buffer can never block the remote command. Success is silent;
                // stderr carries the diagnostic on failure.
                val out = cmd.inputStream.readCapped(MAX_OUTPUT_BYTES)
                val err = cmd.errorStream.readCapped(MAX_OUTPUT_BYTES)
                cmd.join(15, TimeUnit.SECONDS)
                val exit = cmd.exitStatus ?: -1
                if (exit != 0) {
                    throw IOException(
                        "Pubkey-Push fehlgeschlagen (exit=$exit): ${err.ifBlank { out }}"
                    )
                }
            }
        } finally {
            ssh.disconnect()
        }
        learned ?: throw IOException("Host-Key-Fingerprint konnte nicht ermittelt werden")
    }

    override suspend fun verifyPubkeyAuth(config: SshConfig) = withContext(Dispatchers.IO) {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(config.knownHostFingerprint))
        ssh.connect(config.host, config.port)
        try {
            ssh.authPublickey(config.username, BcOpenSshKeyProvider(config.privateKeyPem))
        } finally {
            ssh.disconnect()
        }
    }

    private companion object {
        /** The bootstrap command is silent on success; cap its diagnostics generously. */
        const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}
