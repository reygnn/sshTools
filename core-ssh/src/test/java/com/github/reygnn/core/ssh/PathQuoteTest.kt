package com.github.reygnn.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class PathQuoteTest {

    @Test
    fun `absolute path is fully single-quoted`() {
        assertEquals("'/srv/aabs'", pathQuote("/srv/aabs"))
    }

    @Test
    fun `relative path is fully single-quoted`() {
        assertEquals("'aabs'", pathQuote("aabs"))
    }

    @Test
    fun `tilde-prefixed path keeps tilde unquoted for shell expansion`() {
        // Bash strips the single quotes and concatenates ~/'aabs' →
        // '~/' + 'aabs' = '~/aabs' before tilde expansion runs.
        assertEquals("~/'aabs'", pathQuote("~/aabs"))
    }

    @Test
    fun `bare tilde stays unquoted`() {
        assertEquals("~", pathQuote("~"))
    }

    @Test
    fun `tilde-prefixed path with spaces is still safe`() {
        assertEquals("~/'my aabs'", pathQuote("~/my aabs"))
    }

    @Test
    fun `single quotes inside path are escaped`() {
        // Real shell-injection probe — make sure the quoting stays intact.
        assertEquals("'it'\\''s-aabs'", pathQuote("it's-aabs"))
    }

    @Test
    fun `tilde with embedded single quote still escapes correctly`() {
        assertEquals("~/'don'\\''t'", pathQuote("~/don't"))
    }
}
