package com.github.reygnn.patcher.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parsing of the host's status probe output — no Android, no SSH. */
class ParseUpdateStatusTest {

    @Test
    fun `empty output is idle`() {
        val s = parseUpdateStatus("")
        assertFalse(s.running)
        assertNull(s.aptExitCode)
        assertFalse(s.finished)
        assertFalse(s.rebootRequired)
        assertTrue(s.rebootPackages.isEmpty())
    }

    @Test
    fun `running session is detected`() {
        val s = parseUpdateStatus("RUNNING=1\n")
        assertTrue(s.running)
        assertFalse(s.finished)
    }

    @Test
    fun `finished successful run with pending reboot and packages`() {
        val s = parseUpdateStatus(
            """
            DONE_RC=0
            REBOOT=1
            PKGS=libc6,linux-image-amd64,
            """.trimIndent(),
        )
        assertFalse(s.running)
        assertTrue(s.finished)
        assertTrue(s.succeeded)
        assertEquals(0, s.aptExitCode)
        assertTrue(s.rebootRequired)
        assertEquals(listOf("libc6", "linux-image-amd64"), s.rebootPackages)
    }

    @Test
    fun `finished failed run is not succeeded`() {
        val s = parseUpdateStatus("DONE_RC=100\n")
        assertTrue(s.finished)
        assertFalse(s.succeeded)
        assertEquals(100, s.aptExitCode)
        assertFalse(s.rebootRequired)
    }

    @Test
    fun `reboot required without packages file`() {
        val s = parseUpdateStatus("DONE_RC=0\nREBOOT=1\n")
        assertTrue(s.rebootRequired)
        assertTrue(s.rebootPackages.isEmpty())
    }

    @Test
    fun `non-numeric exit code is treated as not finished`() {
        val s = parseUpdateStatus("DONE_RC=oops\n")
        assertNull(s.aptExitCode)
        assertFalse(s.finished)
    }
}
