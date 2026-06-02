package com.github.reygnn.prodder.ssh

import com.github.reygnn.core.ssh.RemoteCommandException
import com.github.reygnn.core.ssh.connectWithKey
import com.github.reygnn.core.ssh.isScreenNoSessionsOutput
import com.github.reygnn.core.ssh.parseScreenSessions
import com.github.reygnn.core.ssh.runCommand
import com.github.reygnn.core.ssh.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SshjClient(
    private val config: SshConfig,
    private val onLearnHostKey: (String) -> Unit = {},
) : SshClient {

    private fun connect() = connectWithKey(config, onLearnHostKey = onLearnHostKey)

    override suspend fun listSessions(): List<ScreenSession> = withContext(Dispatchers.IO) {
        connect().use { ssh ->
            // `screen -ls` exits non-zero when there are no sessions; treat only
            // that benign case as empty and surface anything else (screen missing,
            // permission denied) as an error instead of "no sessions". See AUDIT V9.
            val (exit, out, err) = ssh.runCommand("LC_ALL=C screen -ls")
            if (exit != 0 && !isScreenNoSessionsOutput(out + err)) {
                throw RemoteCommandException(exit, err.ifBlank { out }.trim())
            }
            parseSessions(out).sortedBy { it.name }
        }
    }

    override suspend fun capture(sessionId: String): String = withContext(Dispatchers.IO) {
        require(isValidSessionId(sessionId)) { "Invalid session id: $sessionId" }
        connect().use { ssh ->
            // `rc=$?` captures the capture-pipeline's status *before* `rm` cleanup,
            // and `exit $rc` propagates it — otherwise the overall exit would be
            // `rm`'s (always 0) and a dead/missing session or failed mktemp would
            // surface as a blank screen instead of an error. See AUDIT V6.
            // A legitimately empty session still exits 0 with empty output.
            val (exit, out, err) = ssh.runCommand(
                "f=\$(mktemp \"\${TMPDIR:-/tmp}/prodder.XXXXXX\") && " +
                    "screen -S ${shellQuote(sessionId)} -X hardcopy \"\$f\" && " +
                    "sleep 0.15 && cat \"\$f\"; rc=\$?; rm -f \"\$f\"; exit \$rc"
            )
            if (exit != 0) throw RemoteCommandException(exit, err.trim())
            out
        }
    }

    override suspend fun sendInput(sessionId: String, payload: String): Boolean =
        withContext(Dispatchers.IO) {
            require(isValidSessionId(sessionId)) { "Invalid session id: $sessionId" }
            connect().use { ssh ->
                val (exit, _, _) = ssh.runCommand("screen -S ${shellQuote(sessionId)} -X stuff ${shellQuote(payload)}")
                exit == 0
            }
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
    parseScreenSessions(output).map { ScreenSession(id = it.id, name = it.name, attached = it.attached) }

internal fun buildStuffPayload(text: String, appendEnter: Boolean): String =
    if (appendEnter) text + "\r" else text
