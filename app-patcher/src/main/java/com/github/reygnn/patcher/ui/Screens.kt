package com.github.reygnn.patcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.reygnn.patcher.R
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ui.KeyField
import com.github.reygnn.core.ui.LogLineRow
import com.github.reygnn.core.ui.ServerEditor
import com.github.reygnn.core.ui.ServerPicker
import com.github.reygnn.core.ui.ServerRow
import com.github.reygnn.core.ui.resolve

/* ------------------------------------------------------------------ */
/* Update: poll state, stream the apt log, offer reboot                 */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    viewModel: UpdateViewModel,
    versionName: String,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sel by viewModel.serverSelection.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.update_title, versionName)) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.watching) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_status))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.open_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (sel.servers.size > 1 && !state.watching) {
                ServerPicker(
                    serverNames = sel.servers.map { it.name },
                    selectedIndex = sel.selectedIndex,
                    onSelect = viewModel::selectServer,
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.watching -> UpdateStreaming(
                        log = state.log,
                        onStop = viewModel::stopWatching,
                    )

                    !state.hasLoadedOnce && state.loading ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    else -> UpdateStatusBody(
                        state = state,
                        onStart = viewModel::startUpdate,
                        onResume = viewModel::resumeWatching,
                        onReboot = viewModel::requestReboot,
                    )
                }
            }
        }
    }

    if (state.pendingReboot) {
        AlertDialog(
            onDismissRequest = viewModel::cancelReboot,
            title = { Text(stringResource(R.string.reboot_confirm_title)) },
            text = { Text(stringResource(R.string.reboot_confirm_body, sel.servers.getOrNull(sel.selectedIndex)?.host ?: "")) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmReboot) { Text(stringResource(R.string.reboot_now)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelReboot) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Live (or replaying) apt log with a spinner and a stop-watching escape. */
@Composable
private fun UpdateStreaming(
    log: List<LogLine>,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.updating), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(12.dp))
        LogSurface(log = log, modifier = Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.size(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        Spacer(Modifier.size(12.dp))
        // The stream has no read timeout (apt can be quiet for minutes); stopping
        // here only detaches the view — the update keeps running on the host.
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.stop_watching))
        }
    }
}

/** Idle / running-detached / finished body, plus the reboot offer when pending. */
@Composable
private fun UpdateStatusBody(
    state: UpdateUiState,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onReboot: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.phase) {
            UpdatePhase.Idle ->
                Text(stringResource(R.string.update_idle), style = MaterialTheme.typography.bodyMedium)

            UpdatePhase.Running ->
                Text(stringResource(R.string.update_running_detached), style = MaterialTheme.typography.bodyMedium)

            UpdatePhase.Finished -> {
                val msg = if (state.succeeded) stringResource(R.string.update_succeeded)
                else stringResource(R.string.update_failed, state.aptExitCode?.toString() ?: "—")
                Text(
                    msg,
                    color = if (state.succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (state.log.isNotEmpty()) {
            LogSurface(log = state.log, modifier = Modifier.fillMaxWidth().height(280.dp))
        }

        if (state.rebootRequired) {
            HorizontalDivider()
            Text(stringResource(R.string.reboot_required), style = MaterialTheme.typography.titleMedium)
            if (state.rebootPackages.isNotEmpty()) {
                Text(
                    stringResource(R.string.reboot_packages, state.rebootPackages.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onReboot, enabled = !state.rebooting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.rebooting) R.string.rebooting else R.string.reboot_now))
            }
        }

        state.info?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }

        if (state.phase == UpdatePhase.Running) {
            Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.resume_watching))
            }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.phase == UpdatePhase.Finished) R.string.update_again else R.string.start_update))
            }
        }
    }
}

/** Read-only, auto-scrolling, virtualized log view (same shape as Caster's). */
@Composable
private fun LogSurface(log: List<LogLine>, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        val listState = rememberLazyListState()
        LaunchedEffect(log.size) {
            if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp)) {
            items(log) { line -> LogLineRow(line) }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Settings: server profiles + shared SSH key                          */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Server profiles ──
            Text(stringResource(R.string.settings_servers), style = MaterialTheme.typography.titleMedium)
            s.servers.forEachIndexed { i, server ->
                ServerRow(
                    name = server.name,
                    host = server.host,
                    onEdit = { viewModel.editServer(i) },
                    onDelete = { viewModel.deleteServer(i) },
                )
            }
            if (s.editing == null) {
                TextButton(onClick = viewModel::addServer, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.add_server))
                }
            } else {
                val form = s.editing!!
                // Patcher has no working dir — an apt update runs system-wide.
                ServerEditor(
                    name = form.name,
                    host = form.host,
                    port = form.port,
                    username = form.username,
                    workingDir = "",
                    workingDirLabel = null,
                    onName = viewModel::onEditName,
                    onHost = viewModel::onEditHost,
                    onPort = viewModel::onEditPort,
                    onUsername = viewModel::onEditUsername,
                    onWorkingDir = {},
                    onSave = viewModel::saveServer,
                    onCancel = viewModel::cancelEdit,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Shared SSH key (all profiles) ──
            Text(stringResource(R.string.settings_key), style = MaterialTheme.typography.titleMedium)
            KeyField(value = s.privateKeyPem, onValueChange = viewModel::onPrivateKey)
            s.error?.let { err ->
                Text(err.resolve(), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = viewModel::done,
                enabled = !s.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (s.saving) R.string.saving else R.string.done))
            }
        }
    }
}
