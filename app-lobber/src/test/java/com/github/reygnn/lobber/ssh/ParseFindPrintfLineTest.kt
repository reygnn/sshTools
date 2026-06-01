package com.github.reygnn.lobber.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseFindPrintfLineTest {

    @Test
    fun `parses fractional epoch and full path`() {
        val parsed = parseFindPrintfLine("1714680000.5234\t/home/user/apk/app-release.aab")
        assertEquals(AabEntry("app-release.aab", 1714680000L), parsed)
    }

    @Test
    fun `parses integer epoch without fractional part`() {
        val parsed = parseFindPrintfLine("1714680000\t/srv/builds/x.aab")
        assertEquals(AabEntry("x.aab", 1714680000L), parsed)
    }

    @Test
    fun `tolerates path with embedded spaces`() {
        val parsed = parseFindPrintfLine("1714680000\t/home/user/my aabs/v 1.aab")
        assertEquals(AabEntry("v 1.aab", 1714680000L), parsed)
    }

    @Test
    fun `relative path emits bare basename`() {
        val parsed = parseFindPrintfLine("1714680000\tfoo.aab")
        assertEquals(AabEntry("foo.aab", 1714680000L), parsed)
    }

    @Test
    fun `garbage line returns null`() {
        assertNull(parseFindPrintfLine(""))
        assertNull(parseFindPrintfLine("no-tab-here"))
        assertNull(parseFindPrintfLine("\tonly-path"))
        assertNull(parseFindPrintfLine("not-a-number\t/foo.aab"))
    }
}
