package com.github.reygnn.lobber.ssh

import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.SshConnectionParams
import kotlinx.coroutines.flow.Flow

data class SshConfig(
    override val host: String,
    override val port: Int = 22,
    override val username: String,
    val workingDir: String,
    override val privateKeyPem: String,
    override val knownHostFingerprint: String? = null,
) : SshConnectionParams

fun ServerProfile.toSshConfig(privateKeyPem: String) = SshConfig(
    host = host, port = port, username = username,
    workingDir = workingDir, privateKeyPem = privateKeyPem,
    knownHostFingerprint = knownHostFingerprint,
)

data class AabEntry(val name: String, val mtimeEpochSeconds: Long)

interface SshClient {
    suspend fun listAabs(): List<AabEntry>
    fun executeStreaming(command: String): Flow<LogLine>
    suspend fun aabContainsPackage(aabName: String, pkg: String): Boolean
}
