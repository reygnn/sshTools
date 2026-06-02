package com.github.reygnn.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI tests for the shared [ServerPicker] dropdown. Pins that it surfaces
 * the active profile's name and that picking another entry reports the right
 * index back to the caller (the per-app `selectServer`).
 */
@RunWith(RobolectricTestRunner::class)
class ServerPickerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows the selected profile name`() {
        compose.setContent {
            ServerPicker(serverNames = listOf("LAN", "Tailscale"), selectedIndex = 1, onSelect = {})
        }
        compose.onNodeWithText("Tailscale").assertIsDisplayed()
    }

    @Test
    fun `picking another entry reports its index`() {
        var picked = -1
        compose.setContent {
            ServerPicker(serverNames = listOf("LAN", "Tailscale"), selectedIndex = 1, onSelect = { picked = it })
        }
        // Expand the dropdown (anchor field shows the current selection), then pick "LAN".
        compose.onNodeWithText("Tailscale").performClick()
        compose.onNodeWithText("LAN").performClick()
        assertEquals(0, picked)
    }
}
