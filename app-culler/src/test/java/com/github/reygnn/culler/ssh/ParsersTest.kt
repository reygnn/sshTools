package com.github.reygnn.culler.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests — no dispatcher rule, no MockK, no coroutines, per
 * TESTING_CONVENTIONS section 5.
 */
class ParseFindEntriesTest {

    @Test
    fun `parses type letter and name, directories flagged`() {
        val out = "d\tprojects\nf\tnotes.txt\n"
        assertEquals(
            listOf(
                DirEntry("projects", isDirectory = true),
                DirEntry("notes.txt", isDirectory = false),
            ),
            parseFindEntries(out),
        )
    }

    @Test
    fun `keeps dotfiles`() {
        assertEquals(
            listOf(DirEntry(".git", isDirectory = true), DirEntry(".env", isDirectory = false)),
            parseFindEntries("d\t.git\nf\t.env\n"),
        )
    }

    @Test
    fun `treats non-directory types as files`() {
        // `l` (symlink not followed to a dir), `p`, `s`, … are all "not a directory".
        assertEquals(
            listOf(DirEntry("link", isDirectory = false)),
            parseFindEntries("l\tlink\n"),
        )
    }

    @Test
    fun `filters the directory itself and parent`() {
        assertTrue(parseFindEntries("d\t.\nd\t..\n").isEmpty())
    }

    @Test
    fun `ignores blank and malformed lines`() {
        val out = "\ngarbage-without-tab\nd\tkeep\n"
        assertEquals(listOf(DirEntry("keep", isDirectory = true)), parseFindEntries(out))
    }

    @Test
    fun `keeps names containing spaces`() {
        assertEquals(
            listOf(DirEntry("my project", isDirectory = true)),
            parseFindEntries("d\tmy project\n"),
        )
    }
}

class IsSafeEntryNameTest {

    @Test
    fun `accepts a plain basename`() {
        assertTrue(isSafeEntryName("projects"))
        assertTrue(isSafeEntryName(".hidden"))
        assertTrue(isSafeEntryName("a file with spaces"))
        // shellQuote neutralizes metacharacters; only path components are rejected here.
        assertTrue(isSafeEntryName("weird;name"))
    }

    @Test
    fun `rejects empty`() {
        assertFalse(isSafeEntryName(""))
    }

    @Test
    fun `rejects dot and dotdot`() {
        assertFalse(isSafeEntryName("."))
        assertFalse(isSafeEntryName(".."))
    }

    @Test
    fun `rejects anything containing a slash`() {
        assertFalse(isSafeEntryName("a/b"))
        assertFalse(isSafeEntryName("../etc"))
        assertFalse(isSafeEntryName("/abs"))
    }
}
