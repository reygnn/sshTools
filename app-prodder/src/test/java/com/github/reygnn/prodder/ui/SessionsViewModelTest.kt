package com.github.reygnn.prodder.ui

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.prodder.ssh.ScreenSession
import com.github.reygnn.prodder.ssh.SshClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
 * MainDispatcherRule. MockK only, no standalone TestScope.
 */
class SessionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = mockk<SettingsStore>()
    private val client = mockk<SshClient>()
    private val profile = ServerProfile(name = "Server 1", host = "buildserver", username = "ci")

    private lateinit var vm: SessionsViewModel

    @Before
    fun setUp() {
        coEvery { settings.readKeyPem() } returns "PEM"
        // serverSelection combine()s these two flows eagerly in the constructor.
        every { settings.servers } returns flowOf(listOf(profile))
        every { settings.selectedIndex } returns flowOf(0)
        vm = SessionsViewModel(settings = settings, createClient = { client })
    }

    @Test
    fun `loadSessions populates state`() = runTest(mainDispatcherRule.dispatcher) {
        val sessions = listOf(
            ScreenSession("12345.alpha", "alpha", attached = false),
            ScreenSession("12346.beta", "beta", attached = true),
        )
        coEvery { client.listSessions() } returns sessions

        vm.state.test {
            val initial = awaitItem()
            assertFalse(initial.hasLoadedOnce)
            vm.loadSessions()

            val final = expectMostRecentItem()
            assertTrue(final.hasLoadedOnce)
            assertFalse(final.loading)
            assertEquals(sessions, final.sessions)
            assertNull(final.error)
        }
    }

    @Test
    fun `loadSessions surfaces error message`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.listSessions() } throws IOException("screen -ls failed")

        vm.state.test {
            awaitItem()
            vm.loadSessions()

            val final = expectMostRecentItem()
            assertTrue(final.hasLoadedOnce)
            assertFalse(final.loading)
            assertEquals(UiText.Literal("screen -ls failed"), final.error)
        }
    }

    @Test
    fun `selectServer persists index and reloads sessions`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { settings.setSelectedIndex(1) } returns Unit
            coEvery { client.listSessions() } returns emptyList()

            vm.selectServer(1)

            coVerify { settings.setSelectedIndex(1) }
            coVerify { client.listSessions() }
        }

    @Test
    fun `loadSessions sets configured false when no config`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { settings.readKeyPem() } returns null
            val vmNoConfig = SessionsViewModel(settings = settings, createClient = { client })

            vmNoConfig.loadSessions()

            vmNoConfig.state.test {
                assertFalse(expectMostRecentItem().configured)
            }
            coVerify(exactly = 0) { client.listSessions() }
        }
}
