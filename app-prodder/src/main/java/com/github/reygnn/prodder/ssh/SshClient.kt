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
 * Eine screen-Session, wie Prodder sie anzeigt und adressiert. App-lokaler
 * Domain-Typ (analog zu Casters `ProjectEntry`) — strukturell identisch zum
 * Parse-Ergebnis [com.github.reygnn.core.ssh.ScreenSessionInfo], aber bewusst
 * getrennt, damit die core-Primitive nicht in Prodders UI/VM leakt;
 * `parseSessions` mappt das Core-Ergebnis 1:1 hierher.
 */
data class ScreenSession(val id: String, val name: String, val attached: Boolean)

interface SshClient {
    suspend fun listSessions(): List<ScreenSession>
    suspend fun capture(sessionId: String): String
    suspend fun sendInput(sessionId: String, payload: String): Boolean
}
