package com.github.reygnn.core.ssh

/** Eine Zeile SSH-Kommando-Output, wie sie per Flow an die UI geliefert wird. */
sealed interface LogLine {
    data class Stdout(val text: String) : LogLine
    data class Stderr(val text: String) : LogLine
    /** [code] ist `null`, wenn sshj keinen Exit-Status liefert ("unbekannt"). */
    data class ExitCode(val code: Int?) : LogLine
}
