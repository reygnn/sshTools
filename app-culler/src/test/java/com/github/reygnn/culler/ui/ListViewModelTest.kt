package com.github.reygnn.culler.ui

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ssh.RemoteCommandException
import com.github.reygnn.culler.ssh.DirEntry
import com.github.reygnn.culler.ssh.SshClient
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
 * MainDispatcherRule (in test root). Stub every flow the VM reads at
 * construction (servers, selectedIndex, readKeyPem) before building it.
 */
class ListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = mockk<SettingsStore>()
    private val client = mockk<SshClient>()

    private val profile = ServerProfile(
        name = "Server 1",
        host = "buildserver",
        username = "ci",
        workingDir = "~/.claude/projects",
    )

    private lateinit var vm: ListViewModel

    @Before
    fun setUp() {
        coEvery { settings.readKeyPem() } returns "PEM"
        // serverSelection combine()s these two flows eagerly in the constructor —
        // so they must be stubbed, otherwise MockK throws during VM construction.
        every { settings.servers } returns flowOf(listOf(profile))
        every { settings.selectedIndex } returns flowOf(0)
        vm = ListViewModel(settings = settings, createClient = { client })
    }

    @Test
    fun `selectServer persists index and reloads entries`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { settings.setSelectedIndex(1) } returns Unit
            coEvery { client.listEntries() } returns listOf(DirEntry("a", isDirectory = true))

            vm.selectServer(1)

            coVerify { settings.setSelectedIndex(1) }
            coVerify { client.listEntries() }
        }

    @Test
    fun `loadEntries sorts directories first then alphabetically within groups`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.listEntries() } returns listOf(
                DirEntry("gamma.txt", isDirectory = false),
                DirEntry("delta", isDirectory = true),
                DirEntry("alpha.txt", isDirectory = false),
                DirEntry("beta", isDirectory = true),
            )

            vm.state.test {
                val initial = awaitItem()
                assertFalse(initial.hasLoadedOnce)
                vm.loadEntries()

                val final = expectMostRecentItem()
                assertTrue(final.hasLoadedOnce)
                assertFalse(final.loading)
                assertEquals(
                    listOf(
                        DirEntry("beta", isDirectory = true),
                        DirEntry("delta", isDirectory = true),
                        DirEntry("alpha.txt", isDirectory = false),
                        DirEntry("gamma.txt", isDirectory = false),
                    ),
                    final.entries,
                )
                assertNull(final.error)
            }
        }

    @Test
    fun `loadEntries surfaces a generic error message verbatim`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.listEntries() } throws IOException("connection refused")

            vm.state.test {
                awaitItem()
                vm.loadEntries()

                val final = expectMostRecentItem()
                assertTrue(final.hasLoadedOnce)
                assertFalse(final.loading)
                assertEquals(UiText.Literal("connection refused"), final.error)
            }
        }

    @Test
    fun `loadEntries maps RemoteCommandException to the localized resource`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.listEntries() } throws RemoteCommandException(2, "no such dir")

            vm.state.test {
                awaitItem()
                vm.loadEntries()

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
    fun `requestDelete arms the dialog without a remote call`() =
        runTest(mainDispatcherRule.dispatcher) {
            val entry = DirEntry("projects", isDirectory = true)
            vm.requestDelete(entry)

            vm.state.test {
                assertEquals(entry, expectMostRecentItem().pendingDelete)
            }
            coVerify(exactly = 0) { client.deleteEntry(any(), any()) }
        }

    @Test
    fun `cancelDelete clears the pending entry`() =
        runTest(mainDispatcherRule.dispatcher) {
            vm.requestDelete(DirEntry("projects", isDirectory = true))
            vm.cancelDelete()

            vm.state.test {
                assertNull(expectMostRecentItem().pendingDelete)
            }
            coVerify(exactly = 0) { client.deleteEntry(any(), any()) }
        }

    @Test
    fun `confirmDelete deletes the entry and drops it from the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.listEntries() } returns listOf(
                DirEntry("projects", isDirectory = true),
                DirEntry("notes.txt", isDirectory = false),
            )
            coEvery { client.deleteEntry("projects", true) } returns Unit

            vm.loadEntries()
            vm.requestDelete(DirEntry("projects", isDirectory = true))
            vm.confirmDelete()

            vm.state.test {
                val final = expectMostRecentItem()
                assertNull(final.pendingDelete)
                assertNull(final.deleting)
                assertEquals(listOf(DirEntry("notes.txt", isDirectory = false)), final.entries)
                assertNull(final.error)
            }
            coVerify(exactly = 1) { client.deleteEntry("projects", true) }
        }

    @Test
    fun `confirmDelete surfaces error and keeps the entry on failure`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { client.listEntries() } returns listOf(DirEntry("projects", isDirectory = true))
            coEvery { client.deleteEntry("projects", true) } throws
                RemoteCommandException(1, "Permission denied")

            vm.loadEntries()
            vm.requestDelete(DirEntry("projects", isDirectory = true))
            vm.confirmDelete()

            vm.state.test {
                val final = expectMostRecentItem()
                assertNull(final.deleting)
                assertEquals(listOf(DirEntry("projects", isDirectory = true)), final.entries)
                assertEquals(
                    UiText.Resource(
                        com.github.reygnn.core.ui.R.string.cu_error_remote_command,
                        listOf(1, "Permission denied"),
                    ),
                    final.error,
                )
            }
        }

    @Test
    fun `confirmDelete sets configured false when no config`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { settings.readKeyPem() } returns null
            val vmNoConfig = ListViewModel(settings = settings, createClient = { client })

            vmNoConfig.requestDelete(DirEntry("projects", isDirectory = true))
            vmNoConfig.confirmDelete()

            vmNoConfig.state.test {
                val final = expectMostRecentItem()
                assertFalse(final.configured)
                assertNull(final.deleting)
            }
            coVerify(exactly = 0) { client.deleteEntry(any(), any()) }
        }
}
