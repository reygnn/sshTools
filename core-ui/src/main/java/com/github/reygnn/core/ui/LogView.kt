package com.github.reygnn.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.github.reygnn.core.ssh.LogLine

/**
 * One log line (stdout / stderr / exit) in monospace — shared by every screen
 * that streams SSH command output (Lobber install + ADB reconnect, Caster
 * launch). stderr is error-coloured; the exit marker is green on 0, error
 * otherwise, muted when unknown.
 */
@Composable
fun LogLineRow(line: LogLine) {
    val unknown = stringResource(R.string.cu_exit_unknown)
    val (text, color) = when (line) {
        is LogLine.Stdout   -> line.text to Color.Unspecified
        is LogLine.Stderr   -> line.text to MaterialTheme.colorScheme.error
        is LogLine.ExitCode -> when (line.code) {
            null -> "─── exit $unknown ───" to MaterialTheme.colorScheme.onSurfaceVariant
            0    -> "─── exit 0 ───" to MaterialTheme.colorScheme.primary
            else -> "─── exit ${line.code} ───" to MaterialTheme.colorScheme.error
        }
    }
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}
