package com.github.reygnn.prodder.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests — no dispatcher rule, no MockK, no coroutines, per
 * TESTING_CONVENTIONS section 5.
 */
class ParseSessionsTest {

    @Test
    fun `extracts id name and attached state from typical screen -ls output`() {
        val out = """
            There are screens on:
            ${'\t'}12345.claude_alpha${'\t'}(Detached)
            ${'\t'}12346.work-beta${'\t'}(Attached)
            2 Sockets in /run/screen/S-ci.
        """.trimIndent()

        val sessions = parseSessions(out)
        assertEquals(
            listOf(
                ScreenSession(id = "12345.claude_alpha", name = "claude_alpha", attached = false),
                ScreenSession(id = "12346.work-beta", name = "work-beta", attached = true),
            ),
            sessions,
        )
    }

    @Test
    fun `returns empty list when no screens`() {
        val out = "No Sockets found in /run/screen/S-ci."
        assertTrue(parseSessions(out).isEmpty())
    }

    @Test
    fun `ignores header and footer lines`() {
        val out = """
            There are screens on:
            ${'\t'}999.solo${'\t'}(Detached)
            1 Socket in /run/screen/S-ci.
        """.trimIndent()
        assertEquals(listOf("999.solo"), parseSessions(out).map { it.id })
    }

    @Test
    fun `keeps everything after the first dot as the name`() {
        val out = "\t555.my.dotted.app\t(Detached)"
        assertEquals(
            ScreenSession(id = "555.my.dotted.app", name = "my.dotted.app", attached = false),
            parseSessions(out).single(),
        )
    }

    @Test
    fun `treats multi attached as attached`() {
        val out = "\t42.shared\t(Multi, attached)"
        assertTrue(parseSessions(out).single().attached)
    }

    @Test
    fun `deduplicates identical ids`() {
        val out = "\t7.dup\t(Detached)\n\t7.dup\t(Detached)"
        assertEquals(1, parseSessions(out).size)
    }
}

class IsValidSessionIdTest {

    @Test
    fun `accepts pid dot name`() {
        assertTrue(isValidSessionId("12345.claude_alpha"))
        assertTrue(isValidSessionId("1.a-b_c.v2"))
    }

    @Test
    fun `rejects missing pid`() {
        assertFalse(isValidSessionId("claude_alpha"))
        assertFalse(isValidSessionId(".name"))
    }

    @Test
    fun `rejects non-numeric pid`() {
        assertFalse(isValidSessionId("abc.name"))
    }

    @Test
    fun `rejects empty name`() {
        assertFalse(isValidSessionId("123."))
    }

    @Test
    fun `rejects shell metacharacters and traversal`() {
        assertFalse(isValidSessionId("123.a;rm -rf"))
        assertFalse(isValidSessionId("123.a\$(whoami)"))
        assertFalse(isValidSessionId("123.a b"))
        assertFalse(isValidSessionId("123.a/b"))
        assertFalse(isValidSessionId("123.."))
    }

    @Test
    fun `rejects empty and overly long`() {
        assertFalse(isValidSessionId(""))
        assertFalse(isValidSessionId("1." + "x".repeat(200)))
    }
}

class BuildStuffPayloadTest {

    @Test
    fun `appends a carriage return when enter requested`() {
        assertEquals("1\r", buildStuffPayload("1", appendEnter = true))
    }

    @Test
    fun `leaves text untouched when no enter`() {
        assertEquals("1", buildStuffPayload("1", appendEnter = false))
    }

    @Test
    fun `enter only is a lone carriage return`() {
        assertEquals("\r", buildStuffPayload("", appendEnter = true))
    }
}
