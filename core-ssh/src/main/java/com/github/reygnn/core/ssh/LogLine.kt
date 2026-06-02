package com.github.reygnn.core.ssh

/** Eine Zeile SSH-Kommando-Output, wie sie per Flow an die UI geliefert wird. */
sealed interface LogLine {
    data class Stdout(val text: String) : LogLine
    data class Stderr(val text: String) : LogLine
    /** [code] ist `null`, wenn sshj keinen Exit-Status liefert ("unbekannt"). */
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
