package com.github.reygnn.core.ui

import com.github.reygnn.core.ssh.RemoteCommandException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests for the shared error→[UiText] mapping. [toUiText] only *builds* the
 * UiText (resolution happens at render time), so this runs JVM-only — no
 * Android context needed. Covers all three branches once for every app.
 */
class ErrorTextTest {

    @Test
    fun `RemoteCommandException maps to the localized resource with exit and stderr`() {
        val ui = RemoteCommandException(exitStatus = 7, stderr = "boom").toUiText()
        assertTrue(ui is UiText.Resource)
        ui as UiText.Resource
        assertEquals(R.string.cu_error_remote_command, ui.id)
        assertEquals(listOf(7, "boom"), ui.args)
    }

    @Test
    fun `RemoteCommandException with blank stderr substitutes a dash`() {
        val ui = RemoteCommandException(exitStatus = 1, stderr = "   ").toUiText()
        assertEquals(listOf(1, "—"), (ui as UiText.Resource).args)
    }

    @Test
    fun `a throwable with a message surfaces it as a literal`() {
        val ui = IOException("Connection refused").toUiText()
        assertEquals(UiText.Literal("Connection refused"), ui)
    }

    @Test
    fun `a blank or missing message falls back to the unknown-error resource`() {
        assertEquals(UiText.Resource(R.string.cu_error_unknown), IOException("  ").toUiText())
        assertEquals(UiText.Resource(R.string.cu_error_unknown), IOException().toUiText())
    }
}
