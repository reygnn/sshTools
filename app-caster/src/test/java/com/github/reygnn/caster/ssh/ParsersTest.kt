package com.github.reygnn.caster.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests — no dispatcher rule, no MockK, no coroutines, per
 * TESTING_CONVENTIONS section 5.
 */
class ScriptNameToProjectTest {

    @Test
    fun `strips claude prefix and sh suffix`() {
        assertEquals("myproject", scriptNameToProject("claude_myproject.sh"))
    }

    @Test
    fun `keeps internal underscores and dashes`() {
        assertEquals("my_cool-app", scriptNameToProject("claude_my_cool-app.sh"))
    }

    @Test
    fun `rejects file without claude prefix`() {
        assertNull(scriptNameToProject("install-aab.sh"))
    }

    @Test
    fun `rejects file without sh suffix`() {
        assertNull(scriptNameToProject("claude_myproject.txt"))
    }

    @Test
    fun `rejects empty inner name`() {
        assertNull(scriptNameToProject("claude_.sh"))
    }
}

class ParseRunningSessionsTest {

    @Test
    fun `extracts session names from typical screen -ls output`() {
        val out = """
            There are screens on:
            	12345.claude_alpha	(Detached)
            	12346.claude_beta	(Attached)
            2 Sockets in /run/screen/S-ci.
        """.trimIndent()

        val sessions = parseRunningSessions(out)
        assertEquals(setOf("claude_alpha", "claude_beta"), sessions)
    }

    @Test
    fun `returns empty set when no screens`() {
        // `screen -ls` with no sessions prints this and exits 1.
        val out = "No Sockets found in /run/screen/S-ci."
        assertTrue(parseRunningSessions(out).isEmpty())
    }

    @Test
    fun `ignores header and footer lines`() {
        val out = """
            There are screens on:
            	999.claude_solo	(Detached)
            1 Socket in /run/screen/S-ci.
        """.trimIndent()
        assertEquals(setOf("claude_solo"), parseRunningSessions(out))
    }

    @Test
    fun `handles session name containing dots`() {
        // screen names as <pid>.<name>; everything after the first dot is the name.
        val out = "\t555.claude_my.dotted.app\t(Detached)"
        assertEquals(setOf("claude_my.dotted.app"), parseRunningSessions(out))
    }
}

class IsValidProjectNameTest {

    @Test
    fun `accepts plain alnum`() {
        assertTrue(isValidProjectName("myproject"))
        assertTrue(isValidProjectName("app123"))
    }

    @Test
    fun `accepts dash underscore dot`() {
        assertTrue(isValidProjectName("my_cool-app.v2"))
    }

    @Test
    fun `rejects empty`() {
        assertFalse(isValidProjectName(""))
    }

    @Test
    fun `rejects path traversal`() {
        assertFalse(isValidProjectName(".."))
        assertFalse(isValidProjectName("../etc"))
        assertFalse(isValidProjectName("a/b"))
    }

    @Test
    fun `rejects shell metacharacters`() {
        assertFalse(isValidProjectName("a;rm -rf"))
        assertFalse(isValidProjectName("a\$b"))
        assertFalse(isValidProjectName("a b"))
        assertFalse(isValidProjectName("a\$(whoami)"))
    }

    @Test
    fun `rejects overly long name`() {
        assertFalse(isValidProjectName("x".repeat(65)))
    }
}
