package com.github.reygnn.lobber.ssh

import com.github.reygnn.core.ssh.BcOpenSshKeyProvider
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.TofuHostKeyVerifier
import com.github.reygnn.core.ssh.pathQuote
import com.github.reygnn.core.ssh.readCapped
import com.github.reygnn.core.ssh.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.util.concurrent.TimeUnit

class SshjClient(
    private val config: SshConfig,
    private val onLearnHostKey: (String) -> Unit = {},
) : SshClient {

    private fun connect(): SSHClient {
        val ssh = SSHClient()
        ssh.connectTimeout = CONNECT_TIMEOUT_MS
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(config.knownHostFingerprint, onLearnHostKey))
        ssh.connect(config.host, config.port)
        ssh.authPublickey(config.username, BcOpenSshKeyProvider(config.privateKeyPem))
        return ssh
    }

    override suspend fun listAabs(): List<AabEntry> = withContext(Dispatchers.IO) {
        connect().use { ssh ->
            ssh.startSession().use { session ->
                val cmd = session.exec(
                    "find -L ${pathQuote(config.workingDir)} -maxdepth 1 -name '*.aab' -type f -printf '%T@\\t%p\\n'"
                )
                val (out, err) = coroutineScope {
                    val o = async { cmd.inputStream.readCapped(MAX_OUTPUT_BYTES) }
                    val e = async { cmd.errorStream.readCapped(MAX_OUTPUT_BYTES) }
                    o.await() to e.await()
                }
                cmd.join(15, TimeUnit.SECONDS)
                val exit = cmd.exitStatus ?: -1
                if (exit != 0) throw java.io.IOException(
                    "find fehlgeschlagen (exit=$exit) für ${config.workingDir}: ${err.trim().ifEmpty { "(keine stderr)" }}"
                )
                out.lineSequence().filter { it.isNotBlank() }
                    .mapNotNull(::parseFindPrintfLine)
                    .sortedByDescending { it.mtimeEpochSeconds }
                    .toList()
            }
        }
    }

    override suspend fun aabContainsPackage(aabName: String, pkg: String): Boolean =
        withContext(Dispatchers.IO) {
            connect().use { ssh ->
                ssh.startSession().use { session ->
                    val cmd = session.exec(
                        "unzip -p ${pathQuote(config.workingDir)}/${shellQuote(aabName)} " +
                            "base/manifest/AndroidManifest.xml | grep -aFq -- ${shellQuote(pkg)}"
                    )
                    coroutineScope {
                        async { cmd.inputStream.readCapped(MAX_OUTPUT_BYTES) }
                        async { cmd.errorStream.readCapped(MAX_OUTPUT_BYTES) }
                    }
                    cmd.join(15, TimeUnit.SECONDS)
                    cmd.exitStatus == 0
                }
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

    private companion object {
        const val MAX_OUTPUT_BYTES = 1 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}

internal fun parseFindPrintfLine(line: String): AabEntry? {
    val tab = line.indexOf('\t')
    if (tab <= 0) return null
    val epoch = line.substring(0, tab).substringBefore('.').toLongOrNull() ?: return null
    val path = line.substring(tab + 1)
    if (path.isEmpty()) return null
    return AabEntry(name = path.substringAfterLast('/'), mtimeEpochSeconds = epoch)
}
