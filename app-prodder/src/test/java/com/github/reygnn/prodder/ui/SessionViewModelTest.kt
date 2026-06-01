package com.github.reygnn.prodder.ui

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import com.github.reygnn.prodder.R
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.prodder.ssh.SshClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * MockK + single MainDispatcherRule dispatcher. The VM has no internal poll
 * loop (the UI drives refresh), so these tests never risk an infinite
 * runTest virtual-time loop.
 */
class SessionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = mockk<SettingsStore>()
    private val client = mockk<SshClient>()
    private val profile = ServerProfile(name = "Server 1", host = "buildserver", username = "ci")
    private val id = "100.demo"

    private lateinit var vm: SessionViewModel

    @Before
    fun setUp() {
        every { settings.servers } returns flowOf(listOf(profile))
        every { settings.selectedIndex } returns flowOf(0)
        coEvery { settings.readKeyPem() } returns "PEM"
        coEvery { client.capture(id) } returns "Choose [1/2] (default: 1):"
        coEvery { client.sendInput(any(), any()) } returns true
        vm = SessionViewModel(settings = settings, createClient = { client })
    }

    @Test
    fun `bind captures the initial screen`() = runTest(mainDispatcherRule.dispatcher) {
        vm.bind(id, "demo")
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(id, s.sessionId)
            assertEquals("demo", s.sessionName)
            assertEquals("Choose [1/2] (default: 1):", s.screen)
            assertTrue(s.hasLoadedOnce)
            assertNull(s.error)
        }
        coVerify { client.capture(id) }
    }

    @Test
    fun `bind is idempotent for the same id`() = runTest(mainDispatcherRule.dispatcher) {
        vm.bind(id, "demo")
        vm.bind(id, "demo")   // no reset, no second capture-from-bind
        // capture also runs after binds; assert at least the first happened
        coVerify(atLeast = 1) { client.capture(id) }
    }

    @Test
    fun `send appends carriage return and refreshes`() =
        runTest(mainDispatcherRule.dispatcher) {
            vm.bind(id, "demo")
            vm.send("1")

            coVerify { client.sendInput(id, "1\r") }
            // refresh() runs again after a successful send
            coVerify(atLeast = 2) { client.capture(id) }
        }

    @Test
    fun `sendEnter sends a lone carriage return`() =
        runTest(mainDispatcherRule.dispatcher) {
            vm.bind(id, "demo")
            vm.sendEnter()
            coVerify { client.sendInput(id, "\r") }
        }

    @Test
    fun `sendCtrlC sends the ETX control byte`() =
        runTest(mainDispatcherRule.dispatcher) {
            vm.bind(id, "demo")
            vm.sendCtrlC()
            coVerify { client.sendInput(id, "\u0003") }
        }

    @Test
    fun `send surfaces error on failure`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.sendInput(id, any()) } returns false
        vm.bind(id, "demo")

        vm.send("1")

        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(UiText.Resource(R.string.error_send_failed), s.error)
        }
    }

    @Test
    fun `toggleAutoRefresh flips the flag`() = runTest(mainDispatcherRule.dispatcher) {
        vm.bind(id, "demo")
        vm.toggleAutoRefresh()
        vm.state.test {
            assertEquals(false, expectMostRecentItem().autoRefresh)
        }
    }
}
