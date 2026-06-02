package com.github.reygnn.lobber.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.reygnn.core.ssh.LogLine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI tests for the install-progress view: streamed log rendering, the
 * auto-scroll-to-tail (so the user always sees the latest output) and the
 * terminal success/failure verdict driven by the exit code (AUDIT V1 — the exit
 * code is the only failure signal).
 */
@RunWith(RobolectricTestRunner::class)
class InstallProgressTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `renders the title and streamed log lines`() {
        compose.setContent {
            InstallProgress(
                aab = "demo.aab",
                log = listOf(LogLine.Stdout("unpacking"), LogLine.Stderr("a warning")),
                finished = false, exitCode = null, onDismiss = {}, onCancel = {},
            )
        }
        compose.onNodeWithText("demo.aab", substring = true).assertIsDisplayed()
        compose.onNodeWithText("unpacking").assertIsDisplayed()
        compose.onNodeWithText("a warning").assertIsDisplayed()
    }

    @Test
    fun `auto-scrolls so the latest line is visible`() {
        val log = (0 until 60).map { LogLine.Stdout("line-$it") }
        compose.setContent {
            InstallProgress(
                aab = "demo.aab", log = log,
                finished = false, exitCode = null, onDismiss = {}, onCancel = {},
            )
        }
        // The tail would be off-screen (and thus not even composed) without the
        // auto-scroll LaunchedEffect; asserting it is displayed pins that behaviour.
        compose.onNodeWithText("line-59").assertIsDisplayed()
    }

    @Test
    fun `a zero exit shows the success verdict`() {
        compose.setContent {
            InstallProgress(
                aab = "demo.aab", log = listOf(LogLine.ExitCode(0)),
                finished = true, exitCode = 0, onDismiss = {}, onCancel = {},
            )
        }
        compose.onNodeWithText("Installation complete").assertIsDisplayed()
    }

    @Test
    fun `a non-zero exit shows the failure verdict with its code`() {
        compose.setContent {
            InstallProgress(
                aab = "demo.aab", log = listOf(LogLine.ExitCode(7)),
                finished = true, exitCode = 7, onDismiss = {}, onCancel = {},
            )
        }
        compose.onNodeWithText("Installation failed (exit 7)").assertIsDisplayed()
    }
}
