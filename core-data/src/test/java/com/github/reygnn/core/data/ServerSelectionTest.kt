package com.github.reygnn.core.data

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Tests for the shared [serverSelectionState] picker flow — in particular the
 * index clamping, which the per-app ViewModel tests never exercise (they all
 * stub `selectedIndex = 0`). Verified once here for all three apps.
 */
class ServerSelectionTest {

    @get:Rule
    val rule = MainDispatcherRule()

    private fun profile(name: String) = ServerProfile(name = name, host = "h", username = "u")

    @Test
    fun `clamps an out-of-range index to the last entry`() = runTest(rule.dispatcher) {
        val settings = mockk<SettingsStore>()
        every { settings.servers } returns MutableStateFlow(listOf(profile("a"), profile("b")))
        every { settings.selectedIndex } returns MutableStateFlow(5)

        settings.serverSelectionState(backgroundScope).test {
            val selection = expectMostRecentItem()
            assertEquals(2, selection.servers.size)
            assertEquals(1, selection.selectedIndex)
        }
    }

    @Test
    fun `clamps to zero for an empty server list`() = runTest(rule.dispatcher) {
        val settings = mockk<SettingsStore>()
        every { settings.servers } returns MutableStateFlow(emptyList())
        every { settings.selectedIndex } returns MutableStateFlow(3)

        settings.serverSelectionState(backgroundScope).test {
            val selection = expectMostRecentItem()
            assertEquals(0, selection.servers.size)
            assertEquals(0, selection.selectedIndex)
        }
    }
}
