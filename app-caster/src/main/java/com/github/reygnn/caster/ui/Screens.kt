package com.github.reygnn.caster.ui

import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ui.resolve
import com.github.reygnn.core.ui.KeyField
import com.github.reygnn.core.ui.LogLineRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.reygnn.caster.R
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.caster.ssh.ProjectEntry

/* ------------------------------------------------------------------ */
/* Launcher: Projektliste mit Status + Start/Stop                      */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    viewModel: LaunchViewModel,
    versionName: String,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sel by viewModel.serverSelection.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.launcher_title, versionName)) },
                actions = {
                    IconButton(onClick = viewModel::loadProjects) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh_projects),
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
            // Picker nur zeigen, wenn es mehr als ein Profil gibt und gerade
            // kein Start-Stream läuft.
            if (sel.servers.size > 1 && state.launching == null) {
                ServerPicker(
                    serverNames = sel.servers.map { it.name },
                    selectedIndex = sel.selectedIndex,
                    onSelect = viewModel::selectServer,
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.launching != null -> LaunchProgress(
                        project = state.launching!!,
                        log = state.log,
                        finished = state.launchFinished,
                        exitCode = state.lastExitCode,
                        onDismiss = viewModel::dismissLaunch,
                    )

                    !state.hasLoadedOnce && state.loading ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.hasLoadedOnce && state.projects.isEmpty() ->
                        Text(
                            stringResource(R.string.no_projects_found),
                            Modifier.align(Alignment.Center).padding(24.dp),
                        )

                    else -> ProjectList(
                        projects = state.projects,
                        stopping = state.stopping,
                        onStart = viewModel::launch,
                        onStop = viewModel::stop,
                    )
                }
            }
        }
    }

    if (state.pendingRestart != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRestart,
            title = { Text(stringResource(R.string.restart_title)) },
            text = { Text(stringResource(R.string.restart_body, state.pendingRestart!!)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestart) { Text(stringResource(R.string.restart)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestart) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ProjectList(
    projects: List<ProjectEntry>,
    stopping: String?,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(projects, key = { it.name }) { project ->
            ProjectRow(
                project = project,
                isStopping = stopping == project.name,
                onStart = { onStart(project.name) },
                onStop = { onStop(project.name) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectEntry,
    isStopping: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(active = project.running)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (project.running) R.string.status_running else R.string.status_stopped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isStopping) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else if (project.running) {
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
        } else {
            Button(onClick = onStart) { Text(stringResource(R.string.start)) }
        }
    }
}

@Composable
private fun LaunchProgress(
    project: String,
    log: List<LogLine>,
    finished: Boolean,
    exitCode: Int?,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            stringResource(R.string.launching_project, project),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            // LazyColumn (not a verticalScroll Column): a plain Column composes and
            // lays out *every* line eagerly, so each streamed line re-lays-out the
            // whole accumulated log (O(n²) over the stream). The lazy list virtualizes
            // to the visible window — same shape as Lobber's InstallProgress.
            val listState = rememberLazyListState()
            // Follow the streamed launch log to the latest line.
            LaunchedEffect(log.size) {
                if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(log) { line -> LogLineRow(line) }
            }
        }
        Spacer(Modifier.size(12.dp))
        if (finished) {
            // The streaming exit code is the only failure signal — a non-zero (or
            // null = connection dropped) code otherwise reads as success. See AUDIT V1.
            val ok = exitCode == 0
            Text(
                text = if (ok) stringResource(R.string.launch_succeeded)
                else stringResource(R.string.launch_failed, exitCode?.toString() ?: "—"),
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(12.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.back))
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Settings: Server-Profile + geteilter SSH-Key                        */
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
            // ── Server-Profile ──
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

            // ── Geteilter SSH-Key (alle Profile) ──
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

