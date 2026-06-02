package com.github.reygnn.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.github.reygnn.core.ssh.LogLine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI tests for [LogLineRow], run under Robolectric (no device). Pins the
 * text the shared log row renders for each [LogLine] variant — the exit marker in
 * particular is resolved only here (LogLineRow), so a regression in its wording
 * would otherwise go unnoticed.
 */
@RunWith(RobolectricTestRunner::class)
class LogViewTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `stdout renders its text verbatim`() {
        compose.setContent { LogLineRow(LogLine.Stdout("building module :app")) }
        compose.onNodeWithText("building module :app").assertIsDisplayed()
    }

    @Test
    fun `stderr renders its text verbatim`() {
        compose.setContent { LogLineRow(LogLine.Stderr("permission denied")) }
        compose.onNodeWithText("permission denied").assertIsDisplayed()
    }

    @Test
    fun `exit zero renders the success marker`() {
        compose.setContent { LogLineRow(LogLine.ExitCode(0)) }
        compose.onNodeWithText("─── exit 0 ───").assertIsDisplayed()
    }

    @Test
    fun `a non-zero exit renders its code`() {
        compose.setContent { LogLineRow(LogLine.ExitCode(7)) }
        compose.onNodeWithText("─── exit 7 ───").assertIsDisplayed()
    }

    @Test
    fun `an unknown exit renders the unknown marker`() {
        compose.setContent { LogLineRow(LogLine.ExitCode(null)) }
        compose.onNodeWithText("exit", substring = true).assertIsDisplayed()
    }
}
