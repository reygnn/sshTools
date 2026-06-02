package com.github.reygnn.lobber.ssh

import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.RemoteCommandException
import com.github.reygnn.core.ssh.connectWithKey
import com.github.reygnn.core.ssh.pathQuote
import com.github.reygnn.core.ssh.runCommand
import com.github.reygnn.core.ssh.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override suspend fun listAabs(): List<AabEntry> = withContext(Dispatchers.IO) {
        connect().use { ssh ->
            val (exit, out, err) = ssh.runCommand(
                "find -L ${pathQuote(config.workingDir)} -maxdepth 1 -name '*.aab' -type f -printf '%T@\\t%p\\n'"
            )
            if (exit != 0) throw RemoteCommandException(exit, err.trim())
            out.lineSequence().filter { it.isNotBlank() }
                .mapNotNull(::parseFindPrintfLine)
                .sortedByDescending { it.mtimeEpochSeconds }
                .toList()
        }
    }

    override suspend fun aabContainsPackage(aabName: String, pkg: String): Boolean =
        withContext(Dispatchers.IO) {
            connect().use { ssh ->
                val (exit, _, _) = ssh.runCommand(
                    "unzip -p ${pathQuote(config.workingDir)}/${shellQuote(aabName)} " +
                        "base/manifest/AndroidManifest.xml | grep -aFq -- ${shellQuote(pkg)}"
                )
                exit == 0
            }
        }

    override fun executeStreaming(command: String): Flow<LogLine> = channelFlow {
        connect().use { ssh ->
            ssh.startSession().use { session ->
                val cmd = session.exec("cd ${pathQuote(config.workingDir)} && $command")
                coroutineScope {
                    val out = launch(Dispatchers.IO) {
                        cmd.inputStream.bufferedReader().lineSequence()
                            .forEach { send(LogLine.Stdout(it)) }
                    }
                    val err = launch(Dispatchers.IO) {
                        cmd.errorStream.bufferedReader().lineSequence()
                            .forEach { send(LogLine.Stderr(it)) }
                    }
                    out.join(); err.join(); cmd.join()
                    send(LogLine.ExitCode(cmd.exitStatus))
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}

internal fun parseFindPrintfLine(line: String): AabEntry? {
    val tab = line.indexOf('\t')
    if (tab <= 0) return null
    val epoch = line.substring(0, tab).substringBefore('.').toLongOrNull() ?: return null
    val path = line.substring(tab + 1)
    if (path.isEmpty()) return null
    return AabEntry(name = path.substringAfterLast('/'), mtimeEpochSeconds = epoch)
}
