package com.github.reygnn.patcher.ssh

import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.SshConnectionParams
import kotlinx.coroutines.flow.Flow

/** Patcher: no workingDir — an `apt` update runs system-wide, not in a build dir. */
data class SshConfig(
    override val host: String,
    override val port: Int = 22,
    override val username: String,
    override val privateKeyPem: String,
    override val knownHostFingerprint: String? = null,
) : SshConnectionParams

fun ServerProfile.toSshConfig(privateKeyPem: String) = SshConfig(
    host = host, port = port, username = username,
    privateKeyPem = privateKeyPem, knownHostFingerprint = knownHostFingerprint,
)

/**
 * A snapshot of the apt-update state on the host, as Patcher reads it in one
 * poll. The update runs detached in a `screen` session (so a dropped phone
 * connection can't abort dpkg), logging to a fixed file; this status is derived
 * from "is that session alive" + "does the log carry the done-sentinel" +
 * "does the host flag a pending reboot".
 *
 * App-local domain type (analogous to Caster's `ProjectEntry`); the parsing of
 * the raw host output into it lives in [parseUpdateStatus] so it can be unit-tested.
 */
data class UpdateStatus(
    /** The detached update `screen` session is still alive. */
    val running: Boolean,
    /**
     * apt's exit code, parsed from the log's `__PATCHER_DONE__ rc=N` sentinel.
     * `null` while no run has finished yet (no sentinel in the log).
     */
    val aptExitCode: Int?,
    /** `/var/run/reboot-required` exists — the host wants a reboot. */
    val rebootRequired: Boolean,
    /** Packages from `/var/run/reboot-required.pkgs` (may be empty even when [rebootRequired]). */
    val rebootPackages: List<String>,
) {
    /** A run has completed (its sentinel is in the log), regardless of success. */
    val finished: Boolean get() = aptExitCode != null

    /** The last completed run succeeded (`apt` returned 0). */
    val succeeded: Boolean get() = aptExitCode == 0
}

interface SshClient {
    /** One quick poll of the host: running / finished / reboot-pending. */
    suspend fun status(): UpdateStatus

    /**
     * Launch `apt-get update && apt-get full-upgrade` in a detached `screen`
     * session, logging to the fixed log file. A no-op if an update session is
     * already running (so it can't start a second apt over the first).
     */
    suspend fun startUpdate()

    /**
     * Stream the running (or just-finished) update log to completion. Implemented
     * as a host-side poll loop that prints new log lines and exits on the
     * done-sentinel — so the stream terminates cleanly with a [LogLine.ExitCode]
     * once apt is done, and a dropped connection leaves the detached apt running.
     */
    fun streamLog(): Flow<LogLine>

    /**
     * Dispatch `sudo reboot`. Returns `true` once dispatched — the connection is
     * expected to drop as the host goes down, which is treated as success.
     */
    suspend fun reboot(): Boolean
}
