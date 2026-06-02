package com.github.reygnn.lobber.ui

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
 * Compose UI tests for the self-install confirmation (AUDIT V9): installing an
 * AAB that is Lobber itself would kill the running process mid-stream, so the
 * user must confirm. Pins that the AAB name is shown and that the two buttons
 * fire exactly the right callback.
 */
@RunWith(RobolectricTestRunner::class)
class SelfInstallDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows the offending aab name`() {
        compose.setContent {
            SelfInstallDialog(aab = "app-lobber-0.7.1-release.aab", onConfirm = {}, onCancel = {})
        }
        compose.onNodeWithText("app-lobber-0.7.1-release.aab", substring = true).assertIsDisplayed()
    }

    @Test
    fun `continue fires only onConfirm`() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            SelfInstallDialog(aab = "x.aab", onConfirm = { confirmed++ }, onCancel = { cancelled++ })
        }
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, confirmed)
        assertEquals(0, cancelled)
    }

    @Test
    fun `cancel fires only onCancel`() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            SelfInstallDialog(aab = "x.aab", onConfirm = { confirmed++ }, onCancel = { cancelled++ })
        }
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, confirmed)
        assertEquals(1, cancelled)
    }
}
