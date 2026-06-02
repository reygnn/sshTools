package com.github.reygnn.lobber.ssh

import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.RemoteCommandException
import com.github.reygnn.core.ssh.DEFAULT_READ_TIMEOUT_MS
import com.github.reygnn.core.ssh.connectWithKey
import com.github.reygnn.core.ssh.pathQuote
import com.github.reygnn.core.ssh.runCommand
import com.github.reygnn.core.ssh.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SshjClient(
    private val config: SshConfig,
    private val onLearnHostKey: (String) -> Unit = {},
) : SshClient {

    private fun connect(readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS) = connectWithKey(
        host = config.host,
        port = config.port,
        username = config.username,
        privateKeyPem = config.privateKeyPem,
        knownHostFingerprint = config.knownHostFingerprint,
        readTimeoutMs = readTimeoutMs,
        onLearnHostKey = onLearnHostKey,
    )

    override suspend fun listAabs(): List<AabEntry> = withContext(Dispatchers.IO) {
        connect().use { ssh ->
            // LC_ALL=C pins the numeric locale so `%T@`'s fractional part uses a
            // dot (not a comma on e.g. de_DE hosts) — otherwise parseFindPrintfLine
            // drops every entry. See AUDIT V2.
            val (exit, out, err) = ssh.runCommand(
                "LC_ALL=C find -L ${pathQuote(config.workingDir)} -maxdepth 1 -name '*.aab' -type f -printf '%T@\\t%p\\n'"
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
                // Capture the manifest in a var so unzip's failure (corrupt/missing
                // AAB, no unzip) is distinguishable from grep's "no match": `|| exit 3`
                // → exit 3 = could not read the manifest, 0 = package present, 1 =
                // absent. The caller treats anything but 0/1 as "uncertain" and shows
                // the self-install confirmation anyway (fail-safe). See AUDIT V9.
                val (exit, _, err) = ssh.runCommand(
                    "m=\$(unzip -p ${pathQuote(config.workingDir)}/${shellQuote(aabName)} " +
                        "base/manifest/AndroidManifest.xml) || exit 3; " +
                        "printf '%s' \"\$m\" | grep -aFq -- ${shellQuote(pkg)}"
                )
                when (exit) {
                    0 -> true
                    1 -> false
                    else -> throw RemoteCommandException(exit, err.trim())
                }
            }
        }

    override fun executeStreaming(command: String): Flow<LogLine> = channelFlow {
        // readTimeoutMs = 0: an install can legitimately be silent for minutes, so
        // no SO_TIMEOUT here — a stalled stream is recovered by user cancel (which
        // cancels this flow and closes the channel). See AUDIT P1/P2.
        connect(readTimeoutMs = 0).use { ssh ->
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
                    try {
                        out.join(); err.join(); cmd.join()
                        send(LogLine.ExitCode(cmd.exitStatus))
                    } finally {
                        // On cancellation the readers are blocked in readLine(); closing the
                        // channel gives them EOF so they unwind and `.use {}` tears the
                        // connection down promptly instead of waiting for the host. See AUDIT V5.
                        if (!isActive) runCatching { cmd.close() }
                    }
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
