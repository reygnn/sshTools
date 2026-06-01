package com.github.reygnn.core.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM — no Android runtime, no mocks. Pins the JSON persistence shape. */
class ServerProfileTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Lobber/Caster: profile with workingDir ---

    @Test
    fun `list with workingDir round-trips through JSON`() {
        val list = listOf(
            ServerProfile(name = "LAN", host = "192.168.1.10", port = 22, username = "ci", workingDir = "/srv/builds"),
            ServerProfile(name = "Tailscale", host = "build.tail.ts.net", port = 2222, username = "ci", workingDir = "/srv/builds"),
        )

        val decoded = json.decodeFromString<List<ServerProfile>>(json.encodeToString(list))

        assertEquals(list, decoded)
    }

    @Test
    fun `profile with workingDir and fingerprint round-trips through JSON`() {
        val profile = ServerProfile(
            name = "LAN", host = "192.168.1.10", port = 2200, username = "ci",
            workingDir = "/srv", knownHostFingerprint = "SHA256:abc",
        )

        val decoded = json.decodeFromString<ServerProfile>(json.encodeToString(profile))

        assertEquals(profile, decoded)
        assertEquals("SHA256:abc", decoded.knownHostFingerprint)
    }

    // --- Prodder: profile without workingDir ---

    @Test
    fun `list without workingDir round-trips through JSON`() {
        val list = listOf(
            ServerProfile(name = "LAN", host = "192.168.1.10", port = 22, username = "ci"),
            ServerProfile(
                name = "Tailscale", host = "build.tail.ts.net", port = 2222, username = "ci",
                knownHostFingerprint = "SHA256:abc",
            ),
        )

        val decoded = json.decodeFromString<List<ServerProfile>>(json.encodeToString(list))

        assertEquals(list, decoded)
    }

    @Test
    fun `old JSON without workingDir field decodes with empty workingDir default`() {
        val legacy = """[{"name":"LAN","host":"192.168.1.10","port":22,"username":"ci"}]"""

        val decoded = json.decodeFromString<List<ServerProfile>>(legacy)

        assertEquals("", decoded.single().workingDir)
    }

    // --- Shared: fingerprint behaviour ---

    @Test
    fun `unpinned profile round-trips with null fingerprint`() {
        val profile = ServerProfile(name = "LAN", host = "10.0.0.1", port = 22, username = "ci", workingDir = "/srv")

        val decoded = json.decodeFromString<ServerProfile>(json.encodeToString(profile))

        assertNull(decoded.knownHostFingerprint)
        assertEquals(profile, decoded)
    }

    @Test
    fun `old JSON without fingerprint field decodes to null pin`() {
        val legacy = """{"name":"LAN","host":"192.168.1.10","port":22,"username":"ci"}"""

        val decoded = json.decodeFromString<ServerProfile>(legacy)

        assertNull(decoded.knownHostFingerprint)
    }
}
