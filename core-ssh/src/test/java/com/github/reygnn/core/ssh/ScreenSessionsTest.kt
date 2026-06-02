package com.github.reygnn.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-function tests for the shared `screen -ls` parser. */
class ScreenSessionsTest {

    @Test
    fun `extracts id name and attached state from typical output`() {
        val out = """
            There are screens on:
            ${'\t'}12345.claude_alpha${'\t'}(Detached)
            ${'\t'}12346.work-beta${'\t'}(Attached)
            2 Sockets in /run/screen/S-ci.
        """.trimIndent()

        assertEquals(
            listOf(
                ScreenSessionInfo(id = "12345.claude_alpha", name = "claude_alpha", attached = false),
                ScreenSessionInfo(id = "12346.work-beta", name = "work-beta", attached = true),
            ),
            parseScreenSessions(out),
        )
    }

    @Test
    fun `returns empty list when no screens`() {
        // `screen -ls` with no sessions prints this and exits 1.
        assertTrue(parseScreenSessions("No Sockets found in /run/screen/S-ci.").isEmpty())
    }

    @Test
    fun `ignores header and footer lines`() {
        val out = """
            There are screens on:
            ${'\t'}999.solo${'\t'}(Detached)
            1 Socket in /run/screen/S-ci.
        """.trimIndent()
        assertEquals(listOf("999.solo"), parseScreenSessions(out).map { it.id })
    }

    @Test
    fun `keeps everything after the first dot as the name`() {
        val out = "\t555.my.dotted.app\t(Detached)"
        assertEquals(
            ScreenSessionInfo(id = "555.my.dotted.app", name = "my.dotted.app", attached = false),
            parseScreenSessions(out).single(),
        )
    }

    @Test
    fun `treats multi attached as attached`() {
        assertTrue(parseScreenSessions("\t42.shared\t(Multi, attached)").single().attached)
    }

    @Test
    fun `rejects non-numeric pid prefix`() {
        assertTrue(parseScreenSessions("\tabc.name\t(Detached)").isEmpty())
    }

    @Test
    fun `rejects empty name after the dot`() {
        assertTrue(parseScreenSessions("\t123.\t(Detached)").isEmpty())
    }

    @Test
    fun `deduplicates identical ids keeping first-seen order`() {
        val out = "\t7.dup\t(Detached)\n\t7.dup\t(Detached)"
        assertEquals(1, parseScreenSessions(out).size)
    }
}
