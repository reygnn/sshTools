package com.github.reygnn.prodder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.reygnn.prodder.R
import com.github.reygnn.core.onboarding.OnboardingError
import com.github.reygnn.core.onboarding.OnboardingStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onDone: () -> Unit,
    onManual: () -> Unit,
) {
    val c = viewModel.controller
    val s by c.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        c.doneEvents.collect { onDone() }
    }

    val running = s.step != OnboardingStep.Idle && s.step != OnboardingStep.Done

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) })
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = s.host, onValueChange = c::onHost,
                label = { Text(stringResource(R.string.field_host)) }, singleLine = true, enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.port, onValueChange = c::onPort,
                label = { Text(stringResource(R.string.field_port)) }, singleLine = true, enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.username, onValueChange = c::onUsername,
                label = { Text(stringResource(R.string.field_user)) }, singleLine = true, enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.password, onValueChange = c::onPassword,
                label = { Text(stringResource(R.string.field_password_once)) }, singleLine = true, enabled = !running,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            // Prodder has no working dir (it only attaches to existing sessions).

            s.error?.let { err ->
                val msg = when (err) {
                    OnboardingError.EmptyFields -> stringResource(R.string.error_fill_all_fields)
                    is OnboardingError.Failure -> err.message
                }
                Text(msg, color = MaterialTheme.colorScheme.error)
            }

            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Spacer(Modifier.height(0.dp))
                    Text(
                        text = "  " + stepLabel(s.step),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Button(
                onClick = c::start,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (running) R.string.onboarding_running else R.string.onboarding_start))
            }

            TextButton(
                onClick = onManual,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_have_key))
            }
        }
    }

    // Host-key confirmation: shown after phase 1 learns the fingerprint and BEFORE
    // the password is sent. Confirming pins the key; cancelling aborts. See AUDIT V4.
    s.pendingFingerprint?.let { fingerprint ->
        AlertDialog(
            onDismissRequest = c::cancelHostKey,
            title = { Text(stringResource(R.string.onboarding_hostkey_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.onboarding_hostkey_body, s.host.trim()))
                    Text(
                        fingerprint,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        stringResource(R.string.onboarding_hostkey_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = c::confirmHostKey) {
                    Text(stringResource(R.string.onboarding_hostkey_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = c::cancelHostKey) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun stepLabel(step: OnboardingStep): String = when (step) {
    OnboardingStep.Idle                   -> ""
    OnboardingStep.GeneratingKey          -> stringResource(R.string.step_generating)
    OnboardingStep.DiscoveringHost        -> stringResource(R.string.step_discovering)
    OnboardingStep.AwaitingHostKeyConfirm -> stringResource(R.string.step_awaiting_confirm)
    OnboardingStep.PushingKey             -> stringResource(R.string.step_pushing)
    OnboardingStep.Verifying              -> stringResource(R.string.step_verifying)
    OnboardingStep.Saving                 -> stringResource(R.string.step_saving)
    OnboardingStep.Done                   -> stringResource(R.string.step_done)
}
