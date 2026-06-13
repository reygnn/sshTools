package com.github.reygnn.caster.ssh

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

data class ProjectEntry(val name: String, val running: Boolean)

interface SshClient {
    suspend fun listProjects(): List<ProjectEntry>
    fun startStreaming(project: String): Flow<LogLine>
    suspend fun stopSession(project: String): Boolean
    suspend fun isSessionRunning(project: String): Boolean

    /**
     * Runs the host's `gen-project-scripts.sh`, which (re)generates the
     * `claude_*.sh` launch scripts this app lists. Streams its output the same
     * way as [startStreaming]. The script lives at a fixed host location,
     * independent of the per-profile working dir.
     */
    fun generateProjectScripts(): Flow<LogLine>
}
