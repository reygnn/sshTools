package com.github.reygnn.lobber.ssh

import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.ssh.LogLine
import kotlinx.coroutines.flow.Flow

data class SshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val workingDir: String,
    val privateKeyPem: String,
    val knownHostFingerprint: String? = null,
)

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
