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
 * Compose UI tests for the shared [HostKeyConfirmDialog] — the security-critical
 * trust-on-first-use decision (AUDIT V4). Pins that the fingerprint is actually
 * shown to the user and that the two buttons fire exactly the right callback, so
 * a wiring slip can't silently auto-trust or auto-reject a host key.
 */
@RunWith(RobolectricTestRunner::class)
class HostKeyConfirmDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val fingerprint = "SHA256:AbCdEf0123456789zZ"

    @Test
    fun `shows the fingerprint and the target host`() {
        compose.setContent {
            HostKeyConfirmDialog(host = "buildhost", fingerprint = fingerprint, onConfirm = {}, onCancel = {})
        }
        compose.onNodeWithText(fingerprint).assertIsDisplayed()
        compose.onNodeWithText("buildhost", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the trust button fires only onConfirm`() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            HostKeyConfirmDialog(
                host = "h", fingerprint = fingerprint,
                onConfirm = { confirmed++ }, onCancel = { cancelled++ },
            )
        }
        compose.onNodeWithText("Trust & continue").performClick()
        assertEquals(1, confirmed)
        assertEquals(0, cancelled)
    }

    @Test
    fun `the cancel button fires only onCancel`() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            HostKeyConfirmDialog(
                host = "h", fingerprint = fingerprint,
                onConfirm = { confirmed++ }, onCancel = { cancelled++ },
            )
        }
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, confirmed)
        assertEquals(1, cancelled)
    }
}
