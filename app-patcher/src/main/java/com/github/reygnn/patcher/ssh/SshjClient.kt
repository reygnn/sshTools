package com.github.reygnn.patcher.ssh

import com.github.reygnn.core.ssh.DEFAULT_READ_TIMEOUT_MS
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.connectWithKey
import com.github.reygnn.core.ssh.runCommand
import com.github.reygnn.core.ssh.shellQuote
import com.github.reygnn.core.ssh.streamCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Name of the detached `screen` session that carries an apt run. */
private const val SESSION = "patcher_update"

/** Sentinel the update payload appends once apt is done: `__PATCHER_DONE__ rc=<exit>`. */
private const val DONE_MARKER = "__PATCHER_DONE__"

/**
 * Log file the detached run writes to, as a shell expression. User-owned (the
 * shell, not `sudo`, owns the redirection) and under `$HOME` so it survives a
 * `/tmp` wipe and is readable on reconnect.
 */
private const val LOG = "\"\$HOME/.patcher-update.log\""

/**
 * What runs inside the detached `screen` session (via `bash -lc`): refresh the
 * package index, then a fully non-interactive `full-upgrade` (keep existing
 * config files on conflict), and finally append the done-sentinel with apt's
 * exit code. The whole block is redirected to [LOG] (truncating any prior run).
 *
 * `apt-get` is invoked under `sudo` directly (not via `sudo sh -c`) so the host's
 * sudoers `NOPASSWD` rule can stay scoped to `apt-get`. `DEBIAN_FRONTEND` is set
 * as a sudo-side command env var so debconf never blocks on a prompt.
 *
 * This is single-quoted by [shellQuote] when embedded in [launchCmd]; the outer
 * shell hands it to `bash -lc` intact, which then expands `$HOME`/`$?` itself.
 */
private const val UPDATE_PAYLOAD =
    "{ echo '=== apt-get update ==='; " +
        "sudo DEBIAN_FRONTEND=noninteractive apt-get update && " +
        "echo '=== apt-get full-upgrade ===' && " +
        "sudo DEBIAN_FRONTEND=noninteractive apt-get -y " +
        "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold full-upgrade; " +
        "echo \"$DONE_MARKER rc=\$?\"; } > $LOG 2>&1"

/** Exit code [launchCmd] uses to signal "no `screen` binary on the host". */
private const val NO_SCREEN_EXIT = 97

/**
 * Launch the detached update — but only if no update session is already alive,
 * so a second tap can't start a second apt over the first. Bails out with
 * [NO_SCREEN_EXIT] first when `screen` is missing, so [SshjClient.startUpdate]
 * can report it instead of streaming a log that never gets written.
 */
private val launchCmd: String =
    "command -v screen >/dev/null 2>&1 || exit $NO_SCREEN_EXIT; " +
        "screen -ls 2>/dev/null | grep -Eq '\\.$SESSION[[:space:]]' || " +
        "screen -dmS $SESSION bash -lc " + shellQuote(UPDATE_PAYLOAD)

/**
 * Host-side poll loop: print new log lines roughly once a second and exit as soon
 * as the done-sentinel appears. Terminating cleanly (rather than `tail -F`, which
 * would never exit) lets [streamCommand] emit its closing [LogLine.ExitCode].
 * Tolerates a not-yet-created log (waits for it). Plain POSIX sh — runs in the
 * host's default shell.
 */
private val streamCmd: String =
    "LOG=$LOG; n=0; " +
        "while :; do " +
        "if [ -f \"\$LOG\" ]; then tail -n +\$((n+1)) \"\$LOG\"; n=\$(wc -l < \"\$LOG\" 2>/dev/null || echo 0); fi; " +
        "grep -q $DONE_MARKER \"\$LOG\" 2>/dev/null && break; " +
        "sleep 1; " +
        "done"

/**
 * One-shot status probe emitting up to four key=value lines parsed by
 * [parseUpdateStatus]: `RUNNING=1`, `DONE_RC=<n>`, `REBOOT=1`, `PKGS=a,b,c`.
 * Ends in `true` so a final failing test never makes the command exit non-zero.
 */
private val statusCmd: String =
    "LOG=$LOG; " +
        "screen -ls 2>/dev/null | grep -Eq '\\.$SESSION[[:space:]]' && echo 'RUNNING=1'; " +
        "if [ -f \"\$LOG\" ]; then " +
        "rc=\$(grep -o '$DONE_MARKER rc=[0-9-]*' \"\$LOG\" | tail -n1 | sed 's/.*rc=//'); " +
        "[ -n \"\$rc\" ] && echo \"DONE_RC=\$rc\"; fi; " +
        "[ -f /var/run/reboot-required ] && echo 'REBOOT=1'; " +
        "[ -f /var/run/reboot-required.pkgs ] && echo \"PKGS=\$(tr '\\n' ',' < /var/run/reboot-required.pkgs)\"; " +
        "true"

/** Parses [statusCmd]'s key=value output into an [UpdateStatus]. Pure — unit-tested. */
internal fun parseUpdateStatus(raw: String): UpdateStatus {
    var running = false
    var rc: Int? = null
    var reboot = false
    var pkgs = emptyList<String>()
    raw.lineSequence().map { it.trim() }.forEach { line ->
        when {
            line == "RUNNING=1" -> running = true
            line.startsWith("DONE_RC=") -> rc = line.removePrefix("DONE_RC=").toIntOrNull()
            line == "REBOOT=1" -> reboot = true
            line.startsWith("PKGS=") -> pkgs = line.removePrefix("PKGS=")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
    return UpdateStatus(running = running, aptExitCode = rc, rebootRequired = reboot, rebootPackages = pkgs)
}

class SshjClient(
    private val config: SshConfig,
    private val onLearnHostKey: (String) -> Unit = {},
) : SshClient {

    private fun connect(readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS) =
        connectWithKey(config, readTimeoutMs = readTimeoutMs, onLearnHostKey = onLearnHostKey)

    override suspend fun status(): UpdateStatus = withContext(Dispatchers.IO) {
        connect().use { ssh ->
            val (_, out, _) = ssh.runCommand(statusCmd)
            parseUpdateStatus(out)
        }
    }

    override suspend fun startUpdate(): Unit = withContext(Dispatchers.IO) {
        connect().use { ssh ->
            val (exit, _, _) = ssh.runCommand(launchCmd)
            if (exit == NO_SCREEN_EXIT) throw ScreenMissingException()
        }
    }

    override fun streamLog(): Flow<LogLine> =
        // readTimeoutMs = 0: the poll loop is intentionally quiet between log
        // updates; a stalled stream is recovered by user cancel, not a socket
        // timeout (same as Caster's launch stream). See AUDIT P1/P2.
        streamCommand({ connect(readTimeoutMs = 0) }, streamCmd)

    override suspend fun reboot(): Boolean = withContext(Dispatchers.IO) {
        // connect() surfaces real connect/auth failures; once past it, `sudo reboot`
        // tears the host (and thus this connection) down, so any error from the
        // command itself is the expected teardown — treat reaching here as dispatched.
        val ssh = connect()
        try {
            runCatching { ssh.runCommand("sudo reboot", timeoutSeconds = 5) }
            true
        } finally {
            runCatching { ssh.disconnect() }
        }
    }
}
