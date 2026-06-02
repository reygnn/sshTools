package com.github.reygnn.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient

/**
 * Streams a remote [command]'s stdout/stderr as [LogLine]s, terminated by a
 * single [LogLine.ExitCode]. App-agnostic: the caller supplies a [connect] thunk
 * (each app keeps its own `SshConfig`/timeout choices, Hard Rule 1) and the
 * fully-built command string; core owns only the delicate concurrency that is
 * otherwise identical — and bug-prone — across apps:
 *
 * - stdout and stderr are drained on separate IO coroutines, so a full channel
 *   buffer can never deadlock the command (mirrors [runCommand]'s drain-first
 *   rule, Hard Rule 3);
 * - on cancellation the command is closed in the `finally`, giving the blocked
 *   `readLine()` readers EOF so they unwind and the connection's `.use {}` tears
 *   down promptly instead of waiting for the host (AUDIT V5).
 *
 * The [connect] thunk typically passes `readTimeoutMs = 0`: a launch/install log
 * can be legitimately silent for minutes, so a stalled stream is recovered by
 * cancelling the collector, not by a socket timeout. See AUDIT P1/P2.
 */
fun streamCommand(connect: () -> SSHClient, command: String): Flow<LogLine> = channelFlow {
    connect().use { ssh ->
        ssh.startSession().use { session ->
            val cmd = session.exec(command)
            coroutineScope {
                val out = launch(Dispatchers.IO) {
                    cmd.inputStream.bufferedReader().lineSequence().forEach { send(LogLine.Stdout(it)) }
                }
                val err = launch(Dispatchers.IO) {
                    cmd.errorStream.bufferedReader().lineSequence().forEach { send(LogLine.Stderr(it)) }
                }
                try {
                    out.join(); err.join(); cmd.join()
                    send(LogLine.ExitCode(cmd.exitStatus))
                } finally {
                    if (!isActive) runCatching { cmd.close() }
                }
            }
        }
    }
}.flowOn(Dispatchers.IO)
