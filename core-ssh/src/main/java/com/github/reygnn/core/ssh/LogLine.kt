package com.github.reygnn.core.ssh

/** A single line of SSH command output, as delivered to the UI via Flow. */
sealed interface LogLine {
    data class Stdout(val text: String) : LogLine
    data class Stderr(val text: String) : LogLine
    /** [code] is `null` when sshj does not provide an exit status ("unknown"). */
    data class ExitCode(val code: Int?) : LogLine
}

/**
 * Default upper bound for a UI-held streaming log. A chatty or long-running
 * remote command would otherwise grow the in-memory line list without limit
 * (the streaming readers are uncapped); keeping only the most recent lines
 * bounds memory while preserving the tail the user actually reads.
 */
const val DEFAULT_MAX_LOG_LINES: Int = 5_000

/**
 * Appends [line] and keeps at most [max] lines, dropping the oldest. Shared by
 * every screen that accumulates streamed [LogLine]s so the cap can't drift.
 */
fun List<LogLine>.plusCapped(line: LogLine, max: Int = DEFAULT_MAX_LOG_LINES): List<LogLine> {
    val appended = this + line
    return if (appended.size <= max) appended else appended.takeLast(max)
}

/**
 * Batch variant of [plusCapped]: appends [lines] in one allocation (used with
 * [chunkedByTime], which delivers coalesced batches) instead of copying the
 * backing list once per line.
 */
fun List<LogLine>.plusCapped(lines: List<LogLine>, max: Int = DEFAULT_MAX_LOG_LINES): List<LogLine> {
    if (lines.isEmpty()) return this
    val appended = this + lines
    return if (appended.size <= max) appended else appended.takeLast(max)
}
