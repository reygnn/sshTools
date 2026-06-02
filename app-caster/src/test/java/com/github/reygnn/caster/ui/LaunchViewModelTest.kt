package com.github.reygnn.caster.ui

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.RemoteCommandException
import com.github.reygnn.caster.ssh.ProjectEntry
import com.github.reygnn.caster.ssh.SshClient
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
class LaunchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = mockk<SettingsStore>()
    private val client = mockk<SshClient>()

    private val profile = ServerProfile(
        name = "Server 1",
        host = "buildserver",
        username = "ci",
        workingDir = "~/projekte",
    )

    private lateinit var vm: LaunchViewModel

    @Before
    fun setUp() {
        coEvery { settings.readKeyPem() } returns "PEM"
        // serverSelection combine()t diese beiden Flows eager beim Konstruktor —
        // müssen also gestubbt sein, sonst wirft MockK beim VM-Bau.
        every { settings.servers } returns flowOf(listOf(profile))
        every { settings.selectedIndex } returns flowOf(0)
        // Default: keine laufende Session — launch() startet ohne Dialog.
        // Tests, die den Restart-Dialog prüfen, überschreiben das.
        coEvery { client.isSessionRunning(any()) } returns false
        vm = LaunchViewModel(settings = settings, createClient = { client })
    }

    @Test
    fun `selectServer persists index and reloads projects`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { settings.setSelectedIndex(1) } returns Unit
            coEvery { client.listProjects() } returns listOf(ProjectEntry("alpha", running = false))

            vm.selectServer(1)

            coVerify { settings.setSelectedIndex(1) }
            coVerify { client.listProjects() }
        }

    @Test
    fun `loadProjects populates state`() = runTest(mainDispatcherRule.dispatcher) {
        // Host (find) liefert unsortiert; die VM sortiert running-zuerst,
        // innerhalb der Gruppen alphabetisch.
        coEvery { client.listProjects() } returns listOf(
            ProjectEntry("alpha", running = false),
            ProjectEntry("beta", running = true),
        )

        vm.state.test {
            val initial = awaitItem()
            assertFalse(initial.hasLoadedOnce)
            vm.loadProjects()

            val final = expectMostRecentItem()
            assertTrue(final.hasLoadedOnce)
            assertFalse(final.loading)
            assertEquals(
                listOf(
                    ProjectEntry("beta", running = true),
                    ProjectEntry("alpha", running = false),
                ),
                final.projects,
            )
            assertNull(final.error)
        }
    }

    @Test
    fun `loadProjects sorts running first then alphabetically within groups`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Host liefert unsortiert; running nach oben, beide Gruppen
            // alphabetisch nach Name.
            coEvery { client.listProjects() } returns listOf(
                ProjectEntry("gamma", running = false),
                ProjectEntry("delta", running = true),
                ProjectEntry("alpha", running = false),
                ProjectEntry("beta", running = true),
            )

            vm.state.test {
                awaitItem()
                vm.loadProjects()

                val final = expectMostRecentItem()
                assertEquals(
                    listOf(
                        ProjectEntry("beta", running = true),
                        ProjectEntry("delta", running = true),
                        ProjectEntry("alpha", running = false),
                        ProjectEntry("gamma", running = false),
                    ),
                    final.projects,
                )
            }
        }

    @Test
    fun `loadProjects surfaces a generic error message verbatim`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.listProjects() } throws IOException("connection refused")

        vm.state.test {
            awaitItem()
            vm.loadProjects()

            val final = expectMostRecentItem()
            assertTrue(final.hasLoadedOnce)
            assertFalse(final.loading)
            assertEquals(UiText.Literal("connection refused"), final.error)
        }
    }

    @Test
    fun `loadProjects maps RemoteCommandException to the localized resource`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.listProjects() } throws RemoteCommandException(2, "no such dir")

            vm.state.test {
                awaitItem()
                vm.loadProjects()

                val final = expectMostRecentItem()
                assertFalse(final.loading)
                assertEquals(
                    UiText.Resource(
                        com.github.reygnn.core.ui.R.string.cu_error_remote_command,
                        listOf(2, "no such dir"),
                    ),
                    final.error,
                )
            }
        }

    @Test
    fun `launch streams log and exit code when no session running`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.isSessionRunning("alpha") } returns false
            every { client.startStreaming("alpha") } returns flow {
                emit(LogLine.Stdout("screen-session claude_alpha gestartet"))
                emit(LogLine.ExitCode(0))
            }

            vm.state.test {
                awaitItem()
                vm.launch("alpha")

                val final = expectMostRecentItem()
                assertEquals("alpha", final.launching)
                assertEquals(0, final.lastExitCode)
                assertTrue(final.launchFinished)
                assertNull(final.pendingRestart)
            }
        }

    @Test
    fun `launch shows restart dialog when session already running`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.isSessionRunning("beta") } returns true

            vm.state.test {
                awaitItem()
                vm.launch("beta")

                val final = expectMostRecentItem()
                assertEquals("beta", final.pendingRestart)
                // Nicht gestartet, solange der Dialog offen ist.
                assertNull(final.launching)
            }
            // startStreaming darf NICHT aufgerufen worden sein.
            coVerify(exactly = 0) { client.startStreaming(any()) }
        }

    @Test
    fun `confirmRestart stops old session then starts`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.isSessionRunning("beta") } returns true
            coEvery { client.stopSession("beta") } returns true
            every { client.startStreaming("beta") } returns flow {
                emit(LogLine.ExitCode(0))
            }

            vm.launch("beta")            // öffnet den Dialog
            vm.confirmRestart()          // bestätigt

            vm.state.test {
                val final = expectMostRecentItem()
                assertNull(final.pendingRestart)
                assertEquals("beta", final.launching)
                assertEquals(0, final.lastExitCode)
            }
            coVerify(exactly = 1) { client.stopSession("beta") }
            coVerify(exactly = 1) { client.startStreaming("beta") }
        }

    @Test
    fun `cancelRestart clears pending without starting`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.isSessionRunning("beta") } returns true

            vm.launch("beta")
            vm.cancelRestart()

            vm.state.test {
                val final = expectMostRecentItem()
                assertNull(final.pendingRestart)
                assertNull(final.launching)
            }
            coVerify(exactly = 0) { client.startStreaming(any()) }
        }

    @Test
    fun `stop marks project not running on success`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.listProjects() } returns listOf(
                ProjectEntry("beta", running = true),
            )
            coEvery { client.stopSession("beta") } returns true

            vm.loadProjects()
            vm.stop("beta")

            vm.state.test {
                val final = expectMostRecentItem()
                assertNull(final.stopping)
                assertEquals(false, final.projects.single { it.name == "beta" }.running)
                assertNull(final.error)
            }
            coVerify(exactly = 1) { client.stopSession("beta") }
        }

    @Test
    fun `stop re-sorts the stopped project below still-running ones`() =
        runTest(mainDispatcherRule.dispatcher) {
            // beta läuft und steht oben; alpha läuft ebenfalls. Nach dem Stop
            // von beta soll es unter das weiterhin laufende alpha rutschen.
            coEvery { client.listProjects() } returns listOf(
                ProjectEntry("beta", running = true),
                ProjectEntry("alpha", running = true),
            )
            coEvery { client.stopSession("beta") } returns true

            vm.loadProjects()
            vm.stop("beta")

            vm.state.test {
                val final = expectMostRecentItem()
                assertEquals(
                    listOf(
                        ProjectEntry("alpha", running = true),
                        ProjectEntry("beta", running = false),
                    ),
                    final.projects,
                )
            }
        }

    @Test
    fun `stop surfaces error on failure`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { client.listProjects() } returns listOf(
            ProjectEntry("beta", running = true),
        )
        coEvery { client.stopSession("beta") } returns false

        vm.loadProjects()
        vm.stop("beta")

        vm.state.test {
            val final = expectMostRecentItem()
            assertNull(final.stopping)
            // running bleibt true, weil Stop fehlschlug
            assertTrue(final.projects.single { it.name == "beta" }.running)
            assertEquals(UiText.Resource(com.github.reygnn.caster.R.string.error_stop_failed), final.error)
        }
    }

    @Test
    fun `dismissLaunch clears log and reloads projects`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.isSessionRunning("alpha") } returns false
            every { client.startStreaming("alpha") } returns flow {
                emit(LogLine.Stdout("hi"))
                emit(LogLine.ExitCode(0))
            }
            // dismissLaunch triggert ein refresh, damit der `running`-Status
            // des gerade gestarteten Projekts in der Liste reflektiert wird.
            val refreshed = listOf(ProjectEntry("alpha", running = true))
            coEvery { client.listProjects() } returns refreshed

            vm.launch("alpha")
            vm.dismissLaunch()

            vm.state.test {
                val final = expectMostRecentItem()
                assertNull(final.launching)
                assertTrue(final.log.isEmpty())
                assertNull(final.lastExitCode)
                assertEquals(refreshed, final.projects)
            }
            coVerify(exactly = 1) { client.listProjects() }
        }

    @Test
    fun `launch sets configured false when no config`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { settings.readKeyPem() } returns null
            val vmNoConfig = LaunchViewModel(settings = settings, createClient = { client })

            vmNoConfig.launch("alpha")

            vmNoConfig.state.test {
                val final = expectMostRecentItem()
                assertFalse(final.configured)
            }
            coVerify(exactly = 0) { client.startStreaming(any()) }
        }

    @Test
    fun `cancelLaunch stops a running launch and returns to the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.isSessionRunning("alpha") } returns false
            // A stream that never completes (no ExitCode) — the stalled-launch case.
            val gate = MutableSharedFlow<LogLine>(replay = 1)
            every { client.startStreaming("alpha") } returns gate

            vm.launch("alpha")
            vm.state.test {
                while (awaitItem().launching == null) { /* wait until streaming */ }
                vm.cancelLaunch()
                val s = expectMostRecentItem()
                assertNull(s.launching)
                assertTrue(s.log.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
