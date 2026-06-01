package com.github.reygnn.caster.ssh

import com.github.reygnn.core.ssh.BcOpenSshKeyProvider
import com.github.reygnn.core.ssh.TofuHostKeyVerifier
import com.github.reygnn.core.ssh.pathQuote
import com.github.reygnn.core.ssh.readCapped
import com.github.reygnn.core.ssh.shellQuote
import com.github.reygnn.core.ssh.LogLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SshjClient(
    private val config: SshConfig,
    private val onHostKeyLearned: suspend (String) -> Unit = {},
) : SshClient {

    private fun connect(learned: AtomicReference<String?>): SSHClient {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(config.knownHostFingerprint) { learned.set(it) })
        ssh.connectTimeout = CONNECT_TIMEOUT_MS
        ssh.connect(config.host, config.port)
        ssh.authPublickey(config.username, BcOpenSshKeyProvider(config.privateKeyPem))
        return ssh
    }

    private suspend fun <T> connected(block: suspend (SSHClient) -> T): T {
        val learned = AtomicReference<String?>(null)
        val result = connect(learned).use { block(it) }
        learned.get()?.let { onHostKeyLearned(it) }
        return result
    }

    private suspend fun SSHClient.run(command: String): Triple<Int, String, String> =
        coroutineScope {
            startSession().use { session ->
                val cmd = session.exec(command)
                val out = async(Dispatchers.IO) { cmd.inputStream.readCapped(MAX_OUTPUT_BYTES) }
                val err = async(Dispatchers.IO) { cmd.errorStream.readCapped(MAX_OUTPUT_BYTES) }
                cmd.join(15, TimeUnit.SECONDS)
                Triple(cmd.exitStatus ?: -1, out.await(), err.await())
            }
        }

    override suspend fun listProjects(): List<ProjectEntry> = withContext(Dispatchers.IO) {
        connected { ssh ->
            val (lsExit, lsOut, lsErr) = ssh.run(
                "find -L ${pathQuote(config.workingDir)} -maxdepth 1 -name 'claude_*.sh' -type f -printf '%f\\n'"
            )
            if (lsExit != 0) throw IOException(
                "Projektsuche fehlgeschlagen (exit=$lsExit) in ${config.workingDir}: ${lsErr.trim().ifEmpty { "(keine stderr)" }}"
            )
            val names = lsOut.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull(::scriptNameToProject).filter(::isValidProjectName).distinct().toList()
            if (names.isEmpty()) return@connected emptyList()
            val (_, screenOut, _) = ssh.run("screen -ls || true")
            val running = parseRunningSessions(screenOut)
            names.map { ProjectEntry(name = it, running = "claude_$it" in running) }.sortedBy { it.name }
        }
    }

    override fun startStreaming(project: String): Flow<LogLine> = channelFlow {
        require(isValidProjectName(project)) { "Ungültiger Projektname: $project" }
        val sessionName = "claude_$project"
        val full = "cd ${pathQuote(config.workingDir)} && " +
            "screen -dmS ${shellQuote(sessionName)} ${shellQuote("./claude_${project}.sh")} && " +
            "echo 'screen-session ${sessionName} gestartet'"
        val learned = AtomicReference<String?>(null)
        connect(learned).use { ssh ->
            ssh.startSession().use { session ->
                val cmd = session.exec(full)
                coroutineScope {
                    val out = launch(Dispatchers.IO) { cmd.inputStream.bufferedReader().lineSequence().forEach { send(LogLine.Stdout(it)) } }
                    val err = launch(Dispatchers.IO) { cmd.errorStream.bufferedReader().lineSequence().forEach { send(LogLine.Stderr(it)) } }
                    out.join(); err.join(); cmd.join()
                    send(LogLine.ExitCode(cmd.exitStatus))
                }
            }
        }
        learned.get()?.let { onHostKeyLearned(it) }
    }.flowOn(Dispatchers.IO)

    override suspend fun stopSession(project: String): Boolean = withContext(Dispatchers.IO) {
        require(isValidProjectName(project))
        connected { ssh -> val (exit, _, _) = ssh.run("screen -S ${shellQuote("claude_$project")} -X quit"); exit == 0 }
    }

    override suspend fun isSessionRunning(project: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidProjectName(project)) return@withContext false
        connected { ssh -> val (_, out, _) = ssh.run("screen -ls || true"); "claude_$project" in parseRunningSessions(out) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val MAX_OUTPUT_BYTES = 1 shl 20
    }
}

internal fun scriptNameToProject(fileName: String): String? {
    if (!fileName.startsWith("claude_") || !fileName.endsWith(".sh")) return null
    return fileName.removePrefix("claude_").removeSuffix(".sh").ifEmpty { null }
}

internal fun isValidProjectName(name: String): Boolean =
    name.isNotEmpty() && name.length <= 64 && name != "." && !name.contains("..") &&
        name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

internal fun parseRunningSessions(screenLsOutput: String): Set<String> =
    screenLsOutput.lineSequence().map { it.trim() }.mapNotNull { line ->
        val token = line.substringBefore('\t').substringBefore(' ').trim()
        val dot = token.indexOf('.')
        if (dot <= 0) return@mapNotNull null
        if (token.substring(0, dot).toIntOrNull() == null) return@mapNotNull null
        token.substring(dot + 1).ifEmpty { null }
    }.toSet()
