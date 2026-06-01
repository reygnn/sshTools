package com.github.reygnn.lobber.ui

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import com.github.reygnn.lobber.R
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.lobber.ssh.SshClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Follows TESTING_CONVENTIONS: single dispatcher via the project's
 * MainDispatcherRule. Covers server-profile management and the ADB-reconnect
 * path; the shared streaming machinery is also covered by InstallViewModelTest.
 */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = mockk<SettingsStore>()
    private val client = mockk<SshClient>()
    private val profile = ServerProfile(
        name = "Server 1",
        host = "buildserver",
        username = "ci",
        workingDir = "/srv/builds",
    )

    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        every { settings.selectedIndex } returns flowOf(0)
        every { settings.isConfigured } returns flowOf(true)
        every { settings.servers } returns flowOf(listOf(profile))
        every { settings.adbHost } returns flowOf("100.64.0.2")
        coEvery { settings.readKeyPem() } returns "PEM"
        coEvery { settings.saveAdbHost(any()) } returns Unit
        coEvery { settings.saveServers(any()) } returns Unit
        coEvery { settings.saveKey(any()) } returns Unit
        vm = SettingsViewModel(settings = settings, createClient = { client })
    }

    // ── Server-Profile ────────────────────────────────────────────

    @Test
    fun `saveServer appends a new profile and persists`() = runTest(mainDispatcherRule.dispatcher) {
        vm.addServer()
        vm.onEditName("LAN")
        vm.onEditHost("192.168.1.10")
        vm.onEditPort("22")
        vm.onEditUsername("ci")
        vm.onEditWorkingDir("/srv/builds")
        vm.saveServer()

        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(2, final.servers.size)
            assertEquals("LAN", final.servers[1].name)
            assertNull(final.editing)
        }
        coVerify { settings.saveServers(match { it.size == 2 && it[1].name == "LAN" }) }
    }

    @Test
    fun `saveServer with a blank field sets an error and keeps the editor open`() = runTest(mainDispatcherRule.dispatcher) {
        vm.addServer()
        vm.onEditName("LAN") // host/user/dir left blank
        vm.saveServer()

        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(UiText.Resource(R.string.error_fill_all_fields), final.error)
            assertEquals(1, final.servers.size) // nothing added
        }
        coVerify(exactly = 0) { settings.saveServers(any()) }
    }

    @Test
    fun `saveServer rejects an out-of-range port`() = runTest(mainDispatcherRule.dispatcher) {
        vm.addServer()
        vm.onEditName("LAN")
        vm.onEditHost("192.168.1.10")
        vm.onEditUsername("ci")
        vm.onEditWorkingDir("/srv/builds")
        vm.onEditPort("70000") // digits, but > 65535

        vm.saveServer()

        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(UiText.Resource(R.string.error_invalid_port), final.error)
        }
        coVerify(exactly = 0) { settings.saveServers(any()) }
    }

    @Test
    fun `editing a profile keeps its pin when host and port are unchanged`() = runTest(mainDispatcherRule.dispatcher) {
        val pinned = ServerProfile(
            name = "S", host = "h", port = 22, username = "ci", workingDir = "/srv",
            knownHostFingerprint = "SHA256:keep",
        )
        every { settings.servers } returns flowOf(listOf(pinned))
        val vm2 = SettingsViewModel(settings = settings, createClient = { client })

        vm2.editServer(0)
        vm2.onEditName("S-renamed") // host/port untouched
        vm2.saveServer()

        vm2.state.test { expectMostRecentItem() }
        coVerify {
            settings.saveServers(
                match { it.size == 1 && it[0].name == "S-renamed" && it[0].knownHostFingerprint == "SHA256:keep" }
            )
        }
    }

    @Test
    fun `editing a profile drops its pin when the host changes`() = runTest(mainDispatcherRule.dispatcher) {
        val pinned = ServerProfile(
            name = "S", host = "h", port = 22, username = "ci", workingDir = "/srv",
            knownHostFingerprint = "SHA256:old",
        )
        every { settings.servers } returns flowOf(listOf(pinned))
        val vm2 = SettingsViewModel(settings = settings, createClient = { client })

        vm2.editServer(0)
        vm2.onEditHost("different-host")
        vm2.saveServer()

        vm2.state.test { expectMostRecentItem() }
        coVerify {
            settings.saveServers(match { it.size == 1 && it[0].knownHostFingerprint == null })
        }
    }

    @Test
    fun `deleteServer removes the profile and persists`() = runTest(mainDispatcherRule.dispatcher) {
        vm.deleteServer(0)

        vm.state.test {
            val final = expectMostRecentItem()
            assertTrue(final.servers.isEmpty())
        }
        coVerify { settings.saveServers(emptyList()) }
    }

    @Test
    fun `done with key and servers saves the key and emits a saved event`() = runTest(mainDispatcherRule.dispatcher) {
        vm.savedEvents.test {
            vm.done()
            awaitItem()
        }
        coVerify { settings.saveKey("PEM") }
    }

    @Test
    fun `done with a blank key sets an error`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { settings.readKeyPem() } returns null
        val vm2 = SettingsViewModel(settings = settings, createClient = { client })

        vm2.done()

        vm2.state.test {
            val final = expectMostRecentItem()
            assertEquals(UiText.Resource(R.string.error_key_required), final.error)
        }
        coVerify(exactly = 0) { settings.saveKey(any()) }
    }

    // ── ADB-Reconnect ─────────────────────────────────────────────

    @Test
    fun `reconnectAdb streams the log and clears running on exit`() = runTest(mainDispatcherRule.dispatcher) {
        every { client.executeStreaming(any()) } returns flowOf(
            LogLine.Stdout("connected to 100.64.0.2:5555"),
            LogLine.Stdout("restarting in TCP mode port: 5555"),
            LogLine.ExitCode(0),
        )

        vm.onAdbPort("43217")
        vm.reconnectAdb()

        vm.state.test {
            val final = expectMostRecentItem()
            assertFalse(final.adbRunning)
            assertEquals(3, final.adbLog.size)
            assertEquals(LogLine.ExitCode(0), final.adbLog.last())
        }
    }

    @Test
    fun `reconnectAdb persists the entered adb host`() = runTest(mainDispatcherRule.dispatcher) {
        every { client.executeStreaming(any()) } returns flowOf(LogLine.ExitCode(0))

        vm.onAdbHost("100.64.0.2")
        vm.onAdbPort("5555")
        vm.reconnectAdb()

        vm.state.test { expectMostRecentItem() }
        coVerify { settings.saveAdbHost("100.64.0.2") }
    }

    @Test
    fun `reconnectAdb without a port sets an error and never hits ssh`() = runTest(mainDispatcherRule.dispatcher) {
        vm.onAdbPort("")
        vm.reconnectAdb()

        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(UiText.Resource(R.string.error_adb_fields), final.error)
            assertFalse(final.adbRunning)
        }
        verify(exactly = 0) { client.executeStreaming(any()) }
    }

    @Test
    fun `reconnectAdb builds connect plus tcpip command with the entered endpoint`() = runTest(mainDispatcherRule.dispatcher) {
        val cmd = slot<String>()
        every { client.executeStreaming(capture(cmd)) } returns flowOf(LogLine.ExitCode(0))

        vm.onAdbHost("100.64.0.2")
        vm.onAdbPort("43217")
        vm.reconnectAdb()

        vm.state.test { expectMostRecentItem() }
        assertTrue(cmd.captured.contains("adb connect '100.64.0.2:43217'"))
        assertTrue(cmd.captured.contains("adb tcpip 5555"))
        assertTrue(cmd.captured.contains("adb connect '100.64.0.2:5555'"))
        assertTrue(cmd.captured.contains("adb devices -l"))
    }

    @Test
    fun `reconnectAdb propagates flow errors into state and stops running`() = runTest(mainDispatcherRule.dispatcher) {
        every { client.executeStreaming(any()) } returns flow { throw IOException("ssh closed") }

        vm.onAdbPort("5555")
        vm.reconnectAdb()

        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(UiText.Literal("ssh closed"), final.error)
            assertFalse(final.adbRunning)
        }
    }
}
