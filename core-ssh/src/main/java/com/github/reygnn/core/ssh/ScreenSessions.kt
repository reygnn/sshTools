package com.github.reygnn.core.ssh

/**
 * One parsed `screen -ls` entry. [id] is the raw `<pid>.<name>` token screen
 * uses to address the session, [name] is everything after the first dot, and
 * [attached] is true when screen marks the line as attached (incl.
 * "Multi, attached").
 */
data class ScreenSessionInfo(
    val id: String,
    val name: String,
    val attached: Boolean,
)

/**
 * Parses `screen -ls` output into its well-formed `<pid>.<name>` sessions.
 * Header/footer text and any line without a numeric pid prefix or with an empty
 * name are skipped. Duplicate [id]s are removed, keeping first-seen order.
 *
 * Shared by Caster (which maps to the set of running session names) and Prodder
 * (which surfaces id/name/attached); the token extraction lives here so the two
 * can't drift.
 */
/**
 * GNU screen prints one of these markers (with `LC_ALL=C`) when no sessions
 * exist, and then exits non-zero. Recognising them lets a caller tell the benign
 * "no sessions" case apart from a genuine `screen -ls` failure (screen not
 * installed, permission denied, unreachable socket dir) so the latter is surfaced
 * as an error instead of silently rendered as an empty list. See AUDIT V9.
 */
private val SCREEN_NO_SESSIONS_MARKERS = listOf("No Sockets found", "No screen session found")

/** True if [output] is screen's "no sessions" notice (see [SCREEN_NO_SESSIONS_MARKERS]). */
fun isScreenNoSessionsOutput(output: String): Boolean =
    SCREEN_NO_SESSIONS_MARKERS.any { output.contains(it, ignoreCase = true) }

fun parseScreenSessions(screenLsOutput: String): List<ScreenSessionInfo> =
    screenLsOutput.lineSequence().map { it.trim() }.mapNotNull { line ->
        val token = line.substringBefore('\t').substringBefore(' ').trim()
        val dot = token.indexOf('.')
        if (dot <= 0) return@mapNotNull null
        if (token.substring(0, dot).toIntOrNull() == null) return@mapNotNull null
        val name = token.substring(dot + 1).ifEmpty { return@mapNotNull null }
        // Check the state suffix only, not the whole line: a session whose name
        // contains "attached" (e.g. "999.attached-build") must not be reported
        // as attached when screen marks it (Detached). The state sits after the
        // tab that follows the token; fall back to the remainder after the
        // first space when no tab is present.
        val stateSuffix = if ('\t' in line) line.substringAfter('\t') else line.substringAfter(' ')
        val attached = stateSuffix.contains("attached", ignoreCase = true)
        ScreenSessionInfo(id = token, name = name, attached = attached)
    }.distinctBy { it.id }.toList()
