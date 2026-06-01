package com.github.reygnn.prodder.ssh

import com.github.reygnn.core.ssh.BcOpenSshKeyProvider
import com.github.reygnn.core.ssh.TofuHostKeyVerifier
import com.github.reygnn.core.ssh.readCapped
import com.github.reygnn.core.ssh.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.util.concurrent.TimeUnit

class SshjClient(
    private val config: SshConfig,
    private val onLearnHostKey: (String) -> Unit = {},
) : SshClient {

    private fun connect(): SSHClient {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(config.knownHostFingerprint, onLearnHostKey))
        ssh.connectTimeout = CONNECT_TIMEOUT_MS
        ssh.connect(config.host, config.port)
        ssh.authPublickey(config.username, BcOpenSshKeyProvider(config.privateKeyPem))
        return ssh
    }

    private suspend fun SSHClient.run(command: String): Triple<Int, String, String> =
        startSession().use { session ->
            val cmd = session.exec(command)
            coroutineScope {
                val out = async(Dispatchers.IO) { cmd.inputStream.readCapped(MAX_OUTPUT_BYTES) }
                val err = async(Dispatchers.IO) { cmd.errorStream.readCapped(MAX_OUTPUT_BYTES) }
                val o = out.await(); val e = err.await()
                cmd.join(15, TimeUnit.SECONDS)
                Triple(cmd.exitStatus ?: -1, o, e)
            }
        }

    override suspend fun listSessions(): List<ScreenSession> = withContext(Dispatchers.IO) {
        connect().use { ssh ->
            val (_, out, _) = ssh.run("screen -ls || true")
            parseSessions(out).sortedBy { it.name }
        }
    }

    override suspend fun capture(sessionId: String): String = withContext(Dispatchers.IO) {
        require(isValidSessionId(sessionId)) { "Ungültige Session-ID: $sessionId" }
        connect().use { ssh ->
            val (_, out, _) = ssh.run(
                "f=\$(mktemp \"\${TMPDIR:-/tmp}/prodder.XXXXXX\") && " +
                    "screen -S ${shellQuote(sessionId)} -X hardcopy \"\$f\" && " +
                    "sleep 0.15 && cat \"\$f\"; rm -f \"\$f\""
            )
            out
        }
    }

    override suspend fun sendInput(sessionId: String, payload: String): Boolean =
        withContext(Dispatchers.IO) {
            require(isValidSessionId(sessionId)) { "Ungültige Session-ID: $sessionId" }
            connect().use { ssh ->
                val (exit, _, _) = ssh.run("screen -S ${shellQuote(sessionId)} -X stuff ${shellQuote(payload)}")
                exit == 0
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val MAX_OUTPUT_BYTES = 1 shl 20
    }
}

internal fun isValidSessionId(id: String): Boolean {
    if (id.isEmpty() || id.length > 128) return false
    val dot = id.indexOf('.')
    if (dot <= 0) return false
    if (id.substring(0, dot).toIntOrNull() == null) return false
    val name = id.substring(dot + 1)
    if (name.isEmpty() || name == "." || name == "..") return false
    return name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
}

internal fun parseSessions(output: String): List<ScreenSession> =
    output.lineSequence().map { it.trim() }.mapNotNull { line ->
        val token = line.substringBefore('\t').substringBefore(' ').trim()
        val dot = token.indexOf('.')
        if (dot <= 0) return@mapNotNull null
        if (token.substring(0, dot).toIntOrNull() == null) return@mapNotNull null
        val name = token.substring(dot + 1).ifEmpty { return@mapNotNull null }
        val attached = line.contains("attached", ignoreCase = true)
        ScreenSession(id = token, name = name, attached = attached)
    }.distinctBy { it.id }.toList()

internal fun buildStuffPayload(text: String, appendEnter: Boolean): String =
    if (appendEnter) text + "\r" else text
