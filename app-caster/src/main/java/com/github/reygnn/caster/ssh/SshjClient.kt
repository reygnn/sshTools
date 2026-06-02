package com.github.reygnn.caster.ssh

import com.github.reygnn.core.ssh.connectWithKey
import com.github.reygnn.core.ssh.parseScreenSessions
import com.github.reygnn.core.ssh.pathQuote
import com.github.reygnn.core.ssh.runCommand
import com.github.reygnn.core.ssh.shellQuote
import com.github.reygnn.core.ssh.LogLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class SshjClient(
    private val config: SshConfig,
    private val onLearnHostKey: (String) -> Unit = {},
) : SshClient {

    private fun connect() = connectWithKey(
        host = config.host,
        port = config.port,
        username = config.username,
        privateKeyPem = config.privateKeyPem,
        knownHostFingerprint = config.knownHostFingerprint,
        onLearnHostKey = onLearnHostKey,
    )

    override suspend fun listProjects(): List<ProjectEntry> = withContext(Dispatchers.IO) {
        connect().use { ssh ->
            val (lsExit, lsOut, lsErr) = ssh.runCommand(
                "find -L ${pathQuote(config.workingDir)} -maxdepth 1 -name 'claude_*.sh' -type f -printf '%f\\n'"
            )
            if (lsExit != 0) throw IOException(
                "Projektsuche fehlgeschlagen (exit=$lsExit) in ${config.workingDir}: ${lsErr.trim().ifEmpty { "(keine stderr)" }}"
            )
            val names = lsOut.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull(::scriptNameToProject).filter(::isValidProjectName).distinct().toList()
            if (names.isEmpty()) return@use emptyList()
            val (_, screenOut, _) = ssh.runCommand("screen -ls || true")
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
        connect().use { ssh ->
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
    }.flowOn(Dispatchers.IO)

    override suspend fun stopSession(project: String): Boolean = withContext(Dispatchers.IO) {
        require(isValidProjectName(project))
        connect().use { ssh -> val (exit, _, _) = ssh.runCommand("screen -S ${shellQuote("claude_$project")} -X quit"); exit == 0 }
    }

    override suspend fun isSessionRunning(project: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidProjectName(project)) return@withContext false
        connect().use { ssh -> val (_, out, _) = ssh.runCommand("screen -ls || true"); "claude_$project" in parseRunningSessions(out) }
    }
}

internal fun scriptNameToProject(fileName: String): String? {
    if (!fileName.startsWith("claude_") || !fileName.endsWith(".sh")) return null
    return fileName.removePrefix("claude_").removeSuffix(".sh").ifEmpty { null }
}

internal fun isValidProjectName(name: String): Boolean =
    name.isNotEmpty() && name.length <= 64 && name != "." && !name.contains("..") &&
        name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

/** The set of running screen-session names (Caster keys on `claude_<project>`). */
internal fun parseRunningSessions(screenLsOutput: String): Set<String> =
    parseScreenSessions(screenLsOutput).mapTo(mutableSetOf()) { it.name }
