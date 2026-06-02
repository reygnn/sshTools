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
fun parseScreenSessions(screenLsOutput: String): List<ScreenSessionInfo> =
    screenLsOutput.lineSequence().map { it.trim() }.mapNotNull { line ->
        val token = line.substringBefore('\t').substringBefore(' ').trim()
        val dot = token.indexOf('.')
        if (dot <= 0) return@mapNotNull null
        if (token.substring(0, dot).toIntOrNull() == null) return@mapNotNull null
        val name = token.substring(dot + 1).ifEmpty { return@mapNotNull null }
        val attached = line.contains("attached", ignoreCase = true)
        ScreenSessionInfo(id = token, name = name, attached = attached)
    }.distinctBy { it.id }.toList()
