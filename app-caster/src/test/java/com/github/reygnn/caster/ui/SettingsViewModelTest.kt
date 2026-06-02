package com.github.reygnn.caster.ui
import com.github.reygnn.core.ui.UiText

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import com.github.reygnn.caster.R
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * MockK only, single dispatcher via MainDispatcherRule. The VM reads
 * `settings.servers.first()` + `settings.readKeyPem()` eagerly in `init`, and
 * `settings.isConfigured` for `configState` — all must be stubbed in setUp.
 */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = mockk<SettingsStore>(relaxed = false)
    private val profile = ServerProfile(
        name = "Server 1",
        host = "buildserver",
        username = "ci",
        workingDir = "~/projekte",
    )

    @Before
    fun setUp() {
        every { settings.isConfigured } returns flowOf(true)
        every { settings.servers } returns flowOf(listOf(profile))
        coEvery { settings.readKeyPem() } returns "PEM"
        coEvery { settings.saveServers(any()) } returns Unit
        coEvery { settings.saveKey(any()) } returns Unit
    }

    private fun newVm() = SettingsViewModel(settings)

    @Test
    fun `init loads existing servers and key`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = newVm()
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(listOf(profile), s.servers)
            assertEquals("PEM", s.privateKeyPem)
            assertNull(s.editing)
        }
    }

    @Test
    fun `saveServer adds a new profile and persists`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = newVm()
        vm.addServer()
        vm.onEditName("Tailscale")
        vm.onEditHost("build.tail.ts.net")
        vm.onEditPort("2222")
        vm.onEditUsername("ci")
        vm.onEditWorkingDir("~/projekte")

        val saved = slot<List<ServerProfile>>()
        coEvery { settings.saveServers(capture(saved)) } returns Unit

        vm.saveServer()

        coVerify { settings.saveServers(any()) }
        assertEquals(2, saved.captured.size)
        assertEquals("Tailscale", saved.captured[1].name)
        assertEquals(2222, saved.captured[1].port)
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(2, s.servers.size)
            assertNull(s.editing)
        }
    }

    @Test
    fun `saveServer rejects blank fields`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = newVm()
        vm.addServer()
        vm.onEditName("only-a-name")   // host/user/dir still blank

        vm.saveServer()

        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(UiText.Resource(R.string.error_fill_all_fields), s.error)
        }
        coVerify(exactly = 0) { settings.saveServers(any()) }
    }

    @Test
    fun `editServer populates the form from the profile`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = newVm()
        vm.editServer(0)
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(0, s.editing?.index)
            assertEquals("Server 1", s.editing?.name)
            assertEquals("buildserver", s.editing?.host)
        }
    }

    @Test
    fun `save preserves a host-key pin learned after the editor was opened`() = runTest(mainDispatcherRule.dispatcher) {
        // AUDIT V3: the editor opens on a snapshot without pins, but by save time
        // another profile (B) has been pinned asynchronously (learnHostFingerprint
        // on the application scope). saveServer must re-read the fresh list, not
        // write back the stale snapshot — otherwise B's pin is clobbered.
        val a = ServerProfile(name = "A", host = "host-a", username = "ci", workingDir = "~/p")
        val b = ServerProfile(name = "B", host = "host-b", username = "ci", workingDir = "~/p")
        val bPinned = b.copy(knownHostFingerprint = "SHA256:learned")
        var serversFlow = flowOf(listOf(a, b))
        every { settings.servers } answers { serversFlow }
        val saved = slot<List<ServerProfile>>()
        coEvery { settings.saveServers(capture(saved)) } returns Unit
        val vm = newVm()

        serversFlow = flowOf(listOf(a, bPinned))   // B pinned after the snapshot
        vm.editServer(0)                           // edit the unrelated profile A
        vm.onEditName("A renamed")
        vm.saveServer()

        assertEquals("A renamed", saved.captured[0].name)
        assertEquals("SHA256:learned", saved.captured[1].knownHostFingerprint)
    }

    @Test
    fun `deleteServer removes the profile and persists`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = newVm()
        vm.deleteServer(0)
        coVerify { settings.saveServers(emptyList()) }
        vm.state.test {
            val s = expectMostRecentItem()
            assertTrue(s.servers.isEmpty())
        }
    }

    @Test
    fun `done errors when no server configured`() = runTest(mainDispatcherRule.dispatcher) {
        every { settings.servers } returns flowOf(emptyList())
        coEvery { settings.readKeyPem() } returns "PEM"
        val vm = newVm()

        vm.done()

        vm.state.test {
            assertEquals(UiText.Resource(R.string.error_need_server), expectMostRecentItem().error)
        }
        coVerify(exactly = 0) { settings.saveKey(any()) }
    }

    @Test
    fun `done errors when key blank`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { settings.readKeyPem() } returns ""
        val vm = newVm()

        vm.done()

        vm.state.test {
            assertEquals(UiText.Resource(R.string.error_key_required), expectMostRecentItem().error)
        }
        coVerify(exactly = 0) { settings.saveKey(any()) }
    }

    @Test
    fun `done saves key and emits savedEvent`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = newVm()
        vm.savedEvents.test {
            vm.done()
            awaitItem()   // a saved event was emitted
        }
        coVerify { settings.saveKey("PEM") }
    }
}
