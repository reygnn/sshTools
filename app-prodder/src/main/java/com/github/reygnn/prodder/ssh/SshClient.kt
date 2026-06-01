package com.github.reygnn.prodder.ssh

import com.github.reygnn.core.data.ServerProfile

data class SshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val privateKeyPem: String,
    val knownHostFingerprint: String? = null,
)

/** Prodder: no workingDir in the profile. */
fun ServerProfile.toSshConfig(privateKeyPem: String) = SshConfig(
    host = host, port = port, username = username,
    privateKeyPem = privateKeyPem, knownHostFingerprint = knownHostFingerprint,
)

data class ScreenSession(val id: String, val name: String, val attached: Boolean)

interface SshClient {
    suspend fun listSessions(): List<ScreenSession>
    suspend fun capture(sessionId: String): String
    suspend fun sendInput(sessionId: String, payload: String): Boolean
}
