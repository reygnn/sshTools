package com.github.reygnn.patcher.ui

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.patcher.ssh.SshClient
import com.github.reygnn.patcher.ssh.UpdateStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * MockK only, single dispatcher via MainDispatcherRule (TESTING_CONVENTIONS).
 * `serverSelectionState` combines `servers` + `selectedIndex` eagerly in the
 * constructor, and `resolveConfig()` additionally reads `readKeyPem()` — all
 * three must be stubbed before the VM is built.
 */
class UpdateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = mockk<SettingsStore>()
    private val client = mockk<SshClient>()

    private val profile = ServerProfile(name = "Server 1", host = "vps", username = "root")

    private lateinit var vm: UpdateViewModel

    private fun status(
        running: Boolean = false,
        rc: Int? = null,
        reboot: Boolean = false,
        pkgs: List<String> = emptyList(),
    ) = UpdateStatus(running = running, aptExitCode = rc, rebootRequired = reboot, rebootPackages = pkgs)

    @Before
    fun setUp() {
        coEvery { settings.readKeyPem() } returns "PEM"
        every { settings.servers } returns flowOf(listOf(profile))
        every { settings.selectedIndex } returns flowOf(0)
        vm = UpdateViewModel(settings = settings, createClient = { client })
    }

    @Test
    fun `refresh with idle status settles on Idle`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.status() } returns status()

        vm.state.test {
            awaitItem()
            vm.refresh()
            val final = expectMostRecentItem()
            assertEquals(UpdatePhase.Idle, final.phase)
            assertTrue(final.hasLoadedOnce)
            assertFalse(final.watching)
        }
    }

    @Test
    fun `startUpdate runs then finishes with apt exit code and reboot offer`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.startUpdate() } returns Unit
            every { client.streamLog() } returns flow {
                emit(LogLine.Stdout("=== apt-get update ==="))
                emit(LogLine.Stdout("__PATCHER_DONE__ rc=0"))
                emit(LogLine.ExitCode(0))
            }
            // Post-stream refresh() reads the authoritative final state.
            coEvery { client.status() } returns status(rc = 0, reboot = true, pkgs = listOf("libc6"))

            vm.state.test {
                awaitItem()
                vm.startUpdate()
                val final = expectMostRecentItem()
                assertEquals(UpdatePhase.Finished, final.phase)
                assertEquals(0, final.aptExitCode)
                assertTrue(final.succeeded)
                assertTrue(final.rebootRequired)
                assertEquals(listOf("libc6"), final.rebootPackages)
                assertFalse(final.watching)
                assertTrue(final.log.any { it is LogLine.Stdout })
            }
            coVerify(exactly = 1) { client.startUpdate() }
        }

    @Test
    fun `refresh attaches to a running update and streams the log`() =
        runTest(mainDispatcherRule.dispatcher) {
            // First poll: running -> attach; clean stream end re-polls -> finished.
            coEvery { client.status() } returnsMany listOf(status(running = true), status(rc = 0))
            every { client.streamLog() } returns flow {
                emit(LogLine.Stdout("=== apt-get full-upgrade ==="))
                emit(LogLine.ExitCode(0))
            }

            vm.state.test {
                awaitItem()
                vm.refresh()
                val final = expectMostRecentItem()
                assertEquals(UpdatePhase.Finished, final.phase)
                assertTrue(final.log.any { it is LogLine.Stdout })
            }
            coVerify(atLeast = 1) { client.streamLog() }
        }

    @Test
    fun `startUpdate is ignored while an update is already running`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.status() } returns status(running = true)
            // A stream that never completes keeps the VM in the watching state.
            every { client.streamLog() } returns MutableSharedFlow()

            vm.refresh()           // -> running, watching
            vm.startUpdate()       // must be a no-op

            coVerify(exactly = 0) { client.startUpdate() }
        }

    @Test
    fun `confirmReboot success clears reboot flag and posts a notice`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.reboot() } returns true

            vm.state.test {
                awaitItem()
                vm.requestReboot()
                assertTrue(expectMostRecentItem().pendingReboot)
                vm.confirmReboot()
                val final = expectMostRecentItem()
                assertFalse(final.pendingReboot)
                assertFalse(final.rebooting)
                assertFalse(final.rebootRequired)
                assertNotNull(final.info)
            }
            coVerify(exactly = 1) { client.reboot() }
        }

    @Test
    fun `confirmReboot failure surfaces an error`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.reboot() } returns false

        vm.state.test {
            awaitItem()
            vm.confirmReboot()
            val final = expectMostRecentItem()
            assertFalse(final.rebooting)
            assertNotNull(final.error)
        }
    }
}
