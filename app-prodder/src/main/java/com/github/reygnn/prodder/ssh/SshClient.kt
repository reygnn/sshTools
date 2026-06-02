package com.github.reygnn.prodder.ssh

import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.ssh.SshConnectionParams

data class SshConfig(
    override val host: String,
    override val port: Int = 22,
    override val username: String,
    override val privateKeyPem: String,
    override val knownHostFingerprint: String? = null,
) : SshConnectionParams

/** Prodder: no workingDir in the profile. */
fun ServerProfile.toSshConfig(privateKeyPem: String) = SshConfig(
    host = host, port = port, username = username,
    privateKeyPem = privateKeyPem, knownHostFingerprint = knownHostFingerprint,
)

/**
 * A screen session as Prodder displays and addresses it. App-local
 * domain type (analogous to Caster's `ProjectEntry`) — structurally identical to
 * the parse result [com.github.reygnn.core.ssh.ScreenSessionInfo], but deliberately
 * kept separate so the core primitive does not leak into Prodder's UI/VM;
 * `parseSessions` maps the core result 1:1 to here.
 */
data class ScreenSession(val id: String, val name: String, val attached: Boolean)

interface SshClient {
    suspend fun listSessions(): List<ScreenSession>
    suspend fun capture(sessionId: String): String
    suspend fun sendInput(sessionId: String, payload: String): Boolean
}
