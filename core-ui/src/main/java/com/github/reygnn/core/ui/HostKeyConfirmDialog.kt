package com.github.reygnn.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Trust-on-first-use host-key confirmation, shared by all three apps' onboarding
 * screens (it was identical in each). Shown after phase 1 has learned the
 * fingerprint and **before** the password is sent: confirming pins the key,
 * cancelling (button or outside tap) aborts. See AUDIT V4 and Hard Rule 5.
 *
 * Stateless — takes the learned [fingerprint] and target [host] plus callbacks,
 * holds no ViewModel. Strings live in this module (`cu_…`), matching the rest of
 * the shared UI components.
 */
@Composable
fun HostKeyConfirmDialog(
    host: String,
    fingerprint: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.cu_onboarding_hostkey_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cu_onboarding_hostkey_body, host))
                Text(
                    fingerprint,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.cu_onboarding_hostkey_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.cu_onboarding_hostkey_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cu_cancel))
            }
        },
    )
}
