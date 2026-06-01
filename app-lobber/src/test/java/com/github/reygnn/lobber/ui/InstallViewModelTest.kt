package com.github.reygnn.lobber.ui

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.lobber.ssh.AabEntry
import com.github.reygnn.lobber.ssh.LogLine
import com.github.reygnn.lobber.ssh.SshClient
import com.github.reygnn.lobber.ssh.SshConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Follows TESTING_CONVENTIONS: single dispatcher via the project's
 * MainDispatcherRule (in test root). No standalone TestScope or
 * StandardTestDispatcher created here.
 */
class InstallViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = mockk<SettingsStore>()
    private val client = mockk<SshClient>()
    private val config = SshConfig(
        host = "buildserver",
        username = "ci",
        workingDir = "/srv/builds",
        privateKeyPem = "PEM",
    )
    private val profile = ServerProfile(
        name = "Server 1",
        host = "buildserver",
        username = "ci",
        workingDir = "/srv/builds",
    )

    private lateinit var vm: InstallViewModel

    @Before
    fun setUp() {
        every { settings.config } returns flowOf(config)
        // serverSelection collects these eagerly at construction — must be stubbed.
        every { settings.servers } returns flowOf(listOf(profile))
        every { settings.selectedIndex } returns flowOf(0)
        // Default: AAB doesn't contain our package — install proceeds without
        // the self-install dialog. Tests that exercise the dialog override.
        coEvery { client.aabContainsPackage(any(), any()) } returns false
        vm = InstallViewModel(settings = settings, createClient = { client })
    }

    @Test
    fun `selectServer persists the index and reloads the AAB list`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { settings.setSelectedIndex(1) } returns Unit
        coEvery { client.listAabs() } returns listOf(AabEntry("x.aab", 1L))

        vm.selectServer(1)

        coVerify { settings.setSelectedIndex(1) }
        coVerify { client.listAabs() }
    }

    @Test
    fun `loadAabs populates state with files`() = runTest(mainDispatcherRule.dispatcher) {
        val entries = listOf(
            AabEntry("app-release.aab", 1714680000),
            AabEntry("app-debug.aab", 1714600000),
        )
        coEvery { client.listAabs() } returns entries

        vm.state.test {
            val initial = awaitItem()
            assertEquals(false, initial.hasLoadedOnce)
            vm.loadAabs()

            val final = expectMostRecentItem()
            assertEquals(false, final.loading)
            assertEquals(true, final.hasLoadedOnce)
            assertEquals(entries, final.aabs)
        }
    }

    @Test
    fun `loadAabs reports error when listing fails`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.listAabs() } throws IOException("connection refused")

        vm.state.test {
            awaitItem() // Initialer State
            vm.loadAabs()

            val final = expectMostRecentItem()
            assertEquals(false, final.loading)
            assertEquals(UiText.Literal("connection refused"), final.error)
        }
    }

    @Test
    fun `install keeps installing set after exit so the log stays visible`() = runTest(mainDispatcherRule.dispatcher) {
        every { client.executeStreaming(any()) } returns flowOf(
            LogLine.Stdout("starting"),
            LogLine.Stdout("done"),
            LogLine.ExitCode(0),
        )

        vm.state.test {
            awaitItem() // Initialer State
            vm.install("app-release.aab")

            val final = expectMostRecentItem()
            assertEquals("app-release.aab", final.installing)
            assertTrue(final.installFinished)
            assertEquals(3, final.log.size)
            assertEquals(LogLine.ExitCode(0), final.log.last())
            assertEquals(0, final.lastExitCode)
        }
    }

    @Test
    fun `install with unknown exit propagates null code`() = runTest(mainDispatcherRule.dispatcher) {
        every { client.executeStreaming(any()) } returns flowOf(
            LogLine.Stdout("running"),
            LogLine.ExitCode(null),
        )

        vm.state.test {
            awaitItem()
            vm.install("app-release.aab")

            val final = expectMostRecentItem()
            assertEquals("app-release.aab", final.installing)
            assertTrue(final.installFinished)
            assertEquals(LogLine.ExitCode(null), final.log.last())
            assertNull(final.lastExitCode)
        }
    }

    @Test
    fun `dismissInstall returns to the AAB list and clears the log`() = runTest(mainDispatcherRule.dispatcher) {
        every { client.executeStreaming(any()) } returns flowOf(
            LogLine.Stdout("done"),
            LogLine.ExitCode(0),
        )

        vm.install("app-release.aab")
        vm.state.test {
            // Wait until the install is finished.
            while (!awaitItem().installFinished) { /* drain */ }

            vm.dismissInstall()
            val final = expectMostRecentItem()
            assertNull(final.installing)
            assertTrue(final.log.isEmpty())
            assertNull(final.lastExitCode)
            assertEquals(false, final.installFinished)
        }
    }

    @Test
    fun `loadAabs called twice in a row only hits the client once`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = MutableSharedFlow<List<AabEntry>>(replay = 0)
        coEvery { client.listAabs() } coAnswers { gate.first() }

        vm.loadAabs()
        vm.loadAabs() // Should be a no-op because the first call is still loading.
        gate.emit(listOf(AabEntry("a.aab", 1714680000)))

        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(listOf(AabEntry("a.aab", 1714680000)), final.aabs)
        }
        coVerify(exactly = 1) { client.listAabs() }
    }

    @Test
    fun `loadAabs is a no-op while an install is running`() = runTest(mainDispatcherRule.dispatcher) {
        // Hold the install flow open so we stay in `installing != null`.
        val gate = MutableSharedFlow<LogLine>(replay = 1)
        every { client.executeStreaming(any()) } returns gate
        coEvery { client.listAabs() } returns listOf(
            AabEntry("a.aab", 1714680000),
            AabEntry("b.aab", 1714600000),
        )

        vm.install("app-release.aab")
        vm.state.test {
            // Wait until we're actually in the installing state.
            while (awaitItem().installing == null) { /* drain */ }

            vm.loadAabs() // Should be ignored.

            // Let the install finish.
            gate.emit(LogLine.ExitCode(0))
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { client.listAabs() }
    }

    @Test
    fun `install on AAB whose manifest contains our package asks for confirmation first`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.aabContainsPackage("app-release.aab", any()) } returns true

        vm.state.test {
            awaitItem()
            vm.install("app-release.aab")

            val final = expectMostRecentItem()
            assertEquals("app-release.aab", final.pendingSelfInstall)
            assertNull(final.installing) // not started yet
        }
        coVerify(exactly = 0) { client.executeStreaming(any()) }
    }

    @Test
    fun `confirmSelfInstall starts the actual install`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.aabContainsPackage("app-release.aab", any()) } returns true
        every { client.executeStreaming(any()) } returns flowOf(
            LogLine.Stdout("starting"),
            LogLine.ExitCode(0),
        )

        vm.install("app-release.aab")
        vm.confirmSelfInstall()
        vm.state.test {
            val final = expectMostRecentItem()
            assertNull(final.pendingSelfInstall)
            assertEquals("app-release.aab", final.installing)
            assertTrue(final.installFinished)
        }
    }

    @Test
    fun `cancelSelfInstall clears the pending state without installing`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.aabContainsPackage("app-release.aab", any()) } returns true

        vm.install("app-release.aab")
        vm.cancelSelfInstall()
        vm.state.test {
            val final = expectMostRecentItem()
            assertNull(final.pendingSelfInstall)
            assertNull(final.installing)
        }
        coVerify(exactly = 0) { client.executeStreaming(any()) }
    }

    @Test
    fun `manifest check failure falls back to no warning`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.aabContainsPackage(any(), any()) } throws IOException("unzip not installed")
        every { client.executeStreaming(any()) } returns flowOf(
            LogLine.Stdout("ok"),
            LogLine.ExitCode(0),
        )

        vm.install("app-release.aab")
        vm.state.test {
            val final = expectMostRecentItem()
            assertNull(final.pendingSelfInstall)
            assertEquals("app-release.aab", final.installing)
        }
    }

    @Test
    fun `clearAabs empties the list and resets hasLoadedOnce so the spinner shows next time`() = runTest(mainDispatcherRule.dispatcher) {
        val entries = listOf(AabEntry("a.aab", 1714680000))
        coEvery { client.listAabs() } returns entries

        vm.loadAabs()
        vm.state.test {
            // Wait until first load is in.
            while (!awaitItem().hasLoadedOnce) { /* drain */ }

            vm.clearAabs()
            val cleared = expectMostRecentItem()
            assertTrue(cleared.aabs.isEmpty())
            assertEquals(false, cleared.hasLoadedOnce)
        }
    }

    @Test
    fun `clearAabs is a no-op while an install is running`() = runTest(mainDispatcherRule.dispatcher) {
        // Hold the install flow open so we stay in `installing != null`.
        val gate = MutableSharedFlow<LogLine>(replay = 1)
        every { client.executeStreaming(any()) } returns gate
        coEvery { client.listAabs() } returns listOf(AabEntry("a.aab", 1714680000))

        vm.loadAabs()
        vm.install("app-release.aab")
        vm.state.test {
            // Wait until we're actually in the installing state with the list populated.
            var s = awaitItem()
            while (s.installing == null) s = awaitItem()
            assertEquals(listOf(AabEntry("a.aab", 1714680000)), s.aabs)
            assertEquals(true, s.hasLoadedOnce)

            vm.clearAabs() // Should be ignored — verified by absence of new emissions.
            expectNoEvents()

            gate.emit(LogLine.ExitCode(0))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `install propagates flow errors into state`() = runTest(mainDispatcherRule.dispatcher) {
        every { client.executeStreaming(any()) } returns flow { throw IOException("ssh closed") }

        vm.state.test {
            awaitItem() // Initialer State
            vm.install("app-release.aab")

            val final = expectMostRecentItem()
            assertNull(final.installing)
            assertEquals(UiText.Literal("ssh closed"), final.error)
        }
    }
}
