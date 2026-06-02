package com.github.reygnn.caster.ssh

import com.github.reygnn.core.ssh.DEFAULT_READ_TIMEOUT_MS
import com.github.reygnn.core.ssh.RemoteCommandException
import com.github.reygnn.core.ssh.connectWithKey
import com.github.reygnn.core.ssh.isScreenNoSessionsOutput
import com.github.reygnn.core.ssh.parseScreenSessions
import com.github.reygnn.core.ssh.pathQuote
import com.github.reygnn.core.ssh.runCommand
import com.github.reygnn.core.ssh.shellQuote
import com.github.reygnn.core.ssh.streamCommand
import com.github.reygnn.core.ssh.LogLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SshjClient(
    private val config: SshConfig,
    private val onLearnHostKey: (String) -> Unit = {},
) : SshClient {

    private fun connect(readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS) =
        connectWithKey(config, readTimeoutMs = readTimeoutMs, onLearnHostKey = onLearnHostKey)

    override suspend fun listProjects(): List<ProjectEntry> = withContext(Dispatchers.IO) {
        connect().use { ssh ->
            // LC_ALL=C keeps find/screen output locale-independent (stable parsing). See AUDIT V2.
            val (lsExit, lsOut, lsErr) = ssh.runCommand(
                "LC_ALL=C find -L ${pathQuote(config.workingDir)} -maxdepth 1 -name 'claude_*.sh' -type f -printf '%f\\n'"
            )
            if (lsExit != 0) throw RemoteCommandException(lsExit, lsErr.trim())
            val names = lsOut.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull(::scriptNameToProject).filter(::isValidProjectName).distinct().toList()
            if (names.isEmpty()) return@use emptyList()
            // `screen -ls` exits non-zero when there are no sessions; treat only
            // that benign case as empty and surface anything else (screen missing,
            // permission denied) as an error instead of "no running sessions". See AUDIT V9.
            val (screenExit, screenOut, screenErr) = ssh.runCommand("LC_ALL=C screen -ls")
            if (screenExit != 0 && !isScreenNoSessionsOutput(screenOut + screenErr)) {
                throw RemoteCommandException(screenExit, screenErr.ifBlank { screenOut }.trim())
            }
            val running = parseRunningSessions(screenOut)
            names.map { ProjectEntry(name = it, running = "claude_$it" in running) }.sortedBy { it.name }
        }
    }

    override fun startStreaming(project: String): Flow<LogLine> {
        require(isValidProjectName(project)) { "Invalid project name: $project" }
        val sessionName = "claude_$project"
        val full = "cd ${pathQuote(config.workingDir)} && " +
            "screen -dmS ${shellQuote(sessionName)} ${shellQuote("./claude_${project}.sh")} && " +
            "echo 'screen session ${sessionName} started'"
        // readTimeoutMs = 0: a launch log can be silent for a while; a stalled
        // stream is recovered by user cancel, not a socket timeout. The drain/
        // cancel mechanics live in core-ssh's streamCommand. See AUDIT P1/P2/V5.
        return streamCommand({ connect(readTimeoutMs = 0) }, full)
    }

    override suspend fun stopSession(project: String): Boolean = withContext(Dispatchers.IO) {
        require(isValidProjectName(project))
        connect().use { ssh -> val (exit, _, _) = ssh.runCommand("screen -S ${shellQuote("claude_$project")} -X quit"); exit == 0 }
    }

    override suspend fun isSessionRunning(project: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidProjectName(project)) return@withContext false
        connect().use { ssh ->
            val (exit, out, err) = ssh.runCommand("LC_ALL=C screen -ls")
            if (exit != 0 && !isScreenNoSessionsOutput(out + err)) {
                throw RemoteCommandException(exit, err.ifBlank { out }.trim())
            }
            "claude_$project" in parseRunningSessions(out)
        }
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
