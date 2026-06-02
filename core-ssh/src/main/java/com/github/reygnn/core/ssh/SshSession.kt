package com.github.reygnn.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.schmizz.sshj.SSHClient
import java.util.concurrent.TimeUnit

/** Default cap for a single command's stdout/stderr (1 MiB). */
const val DEFAULT_MAX_OUTPUT_BYTES: Int = 1 shl 20

/** Default connect timeout for a one-shot SSH operation. */
const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 10_000

/** Result of a one-shot remote command: [exitStatus] is -1 when sshj reports none. */
data class CommandResult(val exitStatus: Int, val stdout: String, val stderr: String)

/**
 * A remote command returned a non-zero exit status. Carries [exitStatus] and
 * captured [stderr] so callers surface a *localized* message (mapped UI-side)
 * instead of a hardcoded string. App-agnostic — any SSH command can fail this
 * way — so it lives next to [CommandResult] in core, not in an app.
 */
class RemoteCommandException(
    val exitStatus: Int,
    val stderr: String,
) : java.io.IOException("remote command failed (exit=$exitStatus): ${stderr.ifBlank { "(no stderr)" }}")

/**
 * Opens an [SSHClient], pins the host key trust-on-first-use
 * ([TofuHostKeyVerifier], learned fingerprints reported via [onLearnHostKey]),
 * connects and authenticates with the Ed25519 key in [privateKeyPem].
 *
 * One connection per operation (Hard Rule 3): the caller `.use { }`s the result
 * so it is always torn down. App-shaped config (`SshConfig`) stays per app and
 * is unpacked into these primitives by the caller — core stays app-agnostic.
 */
fun connectWithKey(
    host: String,
    port: Int,
    username: String,
    privateKeyPem: String,
    knownHostFingerprint: String?,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    onLearnHostKey: (String) -> Unit = {},
): SSHClient {
    val ssh = SSHClient()
    ssh.connectTimeout = connectTimeoutMs
    ssh.addHostKeyVerifier(TofuHostKeyVerifier(knownHostFingerprint, onLearnHostKey))
    ssh.connect(host, port)
    ssh.authPublickey(username, BcOpenSshKeyProvider(privateKeyPem))
    return ssh
}

/**
 * Runs [command] on a fresh session and returns its exit status plus captured
 * output. stdout *and* stderr are drained concurrently (each capped at
 * [maxOutputBytes]) and both reads are awaited **before** [cmd.join] — Hard
 * Rule 3: a full channel buffer must never be able to deadlock the command.
 * The join is bounded by [timeoutSeconds].
 */
suspend fun SSHClient.runCommand(
    command: String,
    maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    timeoutSeconds: Long = 15,
): CommandResult = startSession().use { session ->
    val cmd = session.exec(command)
    coroutineScope {
        val out = async(Dispatchers.IO) { cmd.inputStream.readCapped(maxOutputBytes) }
        val err = async(Dispatchers.IO) { cmd.errorStream.readCapped(maxOutputBytes) }
        val stdout = out.await()
        val stderr = err.await()
        cmd.join(timeoutSeconds, TimeUnit.SECONDS)
        CommandResult(cmd.exitStatus ?: -1, stdout, stderr)
    }
}
