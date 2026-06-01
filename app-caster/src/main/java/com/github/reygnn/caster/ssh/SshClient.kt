package com.github.reygnn.caster.ssh

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

data class ProjectEntry(val name: String, val running: Boolean)

interface SshClient {
    suspend fun listProjects(): List<ProjectEntry>
    fun startStreaming(project: String): Flow<LogLine>
    suspend fun stopSession(project: String): Boolean
    suspend fun isSessionRunning(project: String): Boolean
}
