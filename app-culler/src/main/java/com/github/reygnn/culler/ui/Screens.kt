package com.github.reygnn.culler.ui

import com.github.reygnn.core.ui.resolve
import com.github.reygnn.core.ui.KeyField
import com.github.reygnn.core.ui.ServerEditor
import com.github.reygnn.core.ui.ServerPicker
import com.github.reygnn.core.ui.ServerRow
import com.github.reygnn.core.ui.StatusDot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.reygnn.culler.R
import com.github.reygnn.culler.ssh.DirEntry

/* ------------------------------------------------------------------ */
/* List: directory entries with a delete button per row                */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    viewModel: ListViewModel,
    versionName: String,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sel by viewModel.serverSelection.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title, versionName)) },
                actions = {
                    IconButton(onClick = viewModel::loadEntries) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh_entries),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.open_settings),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Only show the picker when there is more than one profile.
            if (sel.servers.size > 1) {
                ServerPicker(
                    serverNames = sel.servers.map { it.name },
                    selectedIndex = sel.selectedIndex,
                    onSelect = viewModel::selectServer,
                )
            }
            state.error?.let { err ->
                Text(
                    err.resolve(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    !state.hasLoadedOnce && state.loading ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.hasLoadedOnce && state.entries.isEmpty() ->
                        Text(
                            stringResource(R.string.no_entries_found),
                            Modifier.align(Alignment.Center).padding(24.dp),
                        )

                    else -> EntryList(
                        entries = state.entries,
                        deleting = state.deleting,
                        onDelete = viewModel::requestDelete,
                    )
                }
            }
        }
    }

    state.pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (entry.isDirectory) R.string.delete_body_dir else R.string.delete_body_file,
                        entry.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun EntryList(
    entries: List<DirEntry>,
    deleting: String?,
    onDelete: (DirEntry) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.name }) { entry ->
            EntryRow(
                entry = entry,
                isDeleting = deleting == entry.name,
                onDelete = { onDelete(entry) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun EntryRow(
    entry: DirEntry,
    isDeleting: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A directory is "active" (filled dot); a plain file is muted.
        StatusDot(active = entry.isDirectory)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (entry.isDirectory) R.string.entry_kind_dir else R.string.entry_kind_file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isDeleting) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else {
            OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
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
            Text(
                stringResource(R.string.settings_servers),
                style = MaterialTheme.typography.titleMedium,
            )
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
                ServerEditor(
                    name = form.name,
                    host = form.host,
                    port = form.port,
                    username = form.username,
                    workingDir = form.workingDir,
                    workingDirLabel = stringResource(R.string.field_working_dir),
                    onName = viewModel::onEditName,
                    onHost = viewModel::onEditHost,
                    onPort = viewModel::onEditPort,
                    onUsername = viewModel::onEditUsername,
                    onWorkingDir = viewModel::onEditWorkingDir,
                    onSave = viewModel::saveServer,
                    onCancel = viewModel::cancelEdit,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Shared SSH key (all profiles) ──
            Text(
                stringResource(R.string.settings_key),
                style = MaterialTheme.typography.titleMedium,
            )
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
