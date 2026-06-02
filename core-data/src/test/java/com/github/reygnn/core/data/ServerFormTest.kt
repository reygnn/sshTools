package com.github.reygnn.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the shared server-form validation / editing helpers. */
class ServerFormTest {

    private val valid = ServerForm(
        index = null, name = "  Build  ", host = "  host  ", port = "2222",
        username = "  ci  ", workingDir = "  ~/builds  ",
    )

    @Test
    fun `validate builds a trimmed profile`() {
        val result = valid.validate(existing = null, requireWorkingDir = true)
        assertTrue(result is ServerFormResult.Valid)
        val profile = (result as ServerFormResult.Valid).profile
        assertEquals("Build", profile.name)
        assertEquals("host", profile.host)
        assertEquals(2222, profile.port)
        assertEquals("ci", profile.username)
        assertEquals("~/builds", profile.workingDir)
        assertNull(profile.knownHostFingerprint)
    }

    @Test
    fun `validate flags blank required fields`() {
        assertEquals(ServerFormResult.EmptyFields, valid.copy(name = "  ").validate(null, true))
        assertEquals(ServerFormResult.EmptyFields, valid.copy(host = "").validate(null, true))
        assertEquals(ServerFormResult.EmptyFields, valid.copy(username = "").validate(null, true))
    }

    @Test
    fun `validate requires working dir only when asked`() {
        val noDir = valid.copy(workingDir = "")
        assertEquals(ServerFormResult.EmptyFields, noDir.validate(null, requireWorkingDir = true))
        assertTrue(noDir.validate(null, requireWorkingDir = false) is ServerFormResult.Valid)
    }

    @Test
    fun `validate rejects out-of-range or non-numeric ports`() {
        assertEquals(ServerFormResult.InvalidPort, valid.copy(port = "0").validate(null, true))
        assertEquals(ServerFormResult.InvalidPort, valid.copy(port = "70000").validate(null, true))
        assertEquals(ServerFormResult.InvalidPort, valid.copy(port = "abc").validate(null, true))
    }

    @Test
    fun `validate keeps the pin while host and port are unchanged`() {
        val existing = ServerProfile(
            name = "old", host = "host", port = 2222, username = "ci",
            workingDir = "~/builds", knownHostFingerprint = "SHA256:abc",
        )
        val result = valid.validate(existing, requireWorkingDir = true)
        assertEquals("SHA256:abc", (result as ServerFormResult.Valid).profile.knownHostFingerprint)
    }

    @Test
    fun `validate drops the pin when the endpoint changes`() {
        val existing = ServerProfile(
            name = "old", host = "other-host", port = 2222, username = "ci",
            workingDir = "~/builds", knownHostFingerprint = "SHA256:abc",
        )
        val result = valid.validate(existing, requireWorkingDir = true)
        assertNull((result as ServerFormResult.Valid).profile.knownHostFingerprint)
    }

    @Test
    fun `toForm round-trips a profile into an editable form`() {
        val profile = ServerProfile(name = "n", host = "h", port = 22, username = "u", workingDir = "w")
        assertEquals(ServerForm(3, "n", "h", "22", "u", "w"), profile.toForm(3))
    }

    @Test
    fun `upsert appends a new profile and replaces an existing one`() {
        val a = ServerProfile(name = "a", host = "ha", username = "u")
        val b = ServerProfile(name = "b", host = "hb", username = "u")
        val c = ServerProfile(name = "c", host = "hc", username = "u")
        assertEquals(listOf(a, b), listOf(a).upsert(index = null, profile = b))
        assertEquals(listOf(a, c), listOf(a, b).upsert(index = 1, profile = c))
    }
}
