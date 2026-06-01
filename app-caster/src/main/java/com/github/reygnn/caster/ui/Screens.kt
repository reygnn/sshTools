package com.github.reygnn.caster.ui

import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ui.resolve

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.reygnn.caster.R
import com.github.reygnn.core.data.ServerProfile
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
                title = { Text(stringRes(R.string.launcher_title, versionName)) },
                actions = {
                    IconButton(onClick = viewModel::loadProjects) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringRes(R.string.refresh_projects),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringRes(R.string.open_settings),
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
                    servers = sel.servers,
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
                            stringRes(R.string.no_projects_found),
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
            title = { Text(stringRes(R.string.restart_title)) },
            text = { Text(stringRes(R.string.restart_body, state.pendingRestart!!)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestart) { Text(stringRes(R.string.restart)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestart) { Text(stringRes(R.string.cancel)) }
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
        StatusDot(running = project.running)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringRes(if (project.running) R.string.status_running else R.string.status_stopped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isStopping) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else if (project.running) {
            OutlinedButton(onClick = onStop) { Text(stringRes(R.string.stop)) }
        } else {
            Button(onClick = onStart) { Text(stringRes(R.string.start)) }
        }
    }
}

@Composable
private fun StatusDot(running: Boolean) {
    val color = if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
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
            stringRes(R.string.launching_project, project),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                log.forEach { line ->
                    when (line) {
                        is LogLine.Stdout -> LogText(line.text, MaterialTheme.colorScheme.onSurface)
                        is LogLine.Stderr -> LogText(line.text, MaterialTheme.colorScheme.error)
                        is LogLine.ExitCode -> {
                            val code = line.code?.toString() ?: stringRes(R.string.exit_unknown)
                            LogText("exit: $code", MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        if (finished) {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringRes(R.string.back))
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

@Composable
private fun LogText(text: String, color: Color) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
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
                title = { Text(stringRes(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringRes(R.string.back)) }
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
                stringRes(R.string.settings_servers),
                style = MaterialTheme.typography.titleMedium,
            )
            s.servers.forEachIndexed { i, server ->
                ServerRow(
                    server = server,
                    onEdit = { viewModel.editServer(i) },
                    onDelete = { viewModel.deleteServer(i) },
                )
            }
            if (s.editing == null) {
                TextButton(onClick = viewModel::addServer, modifier = Modifier.fillMaxWidth()) {
                    Text(stringRes(R.string.add_server))
                }
            } else {
                ServerEditor(form = s.editing!!, viewModel = viewModel)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Geteilter SSH-Key (alle Profile) ──
            Text(
                stringRes(R.string.settings_key),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = s.privateKeyPem, onValueChange = viewModel::onPrivateKey,
                label = { Text(stringRes(R.string.field_private_key)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            s.error?.let { err ->
                Text(err.resolve(), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = viewModel::done,
                enabled = !s.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringRes(if (s.saving) R.string.saving else R.string.done))
            }
        }
    }
}

@Composable
private fun ServerRow(server: ServerProfile, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name)
                Text(
                    text = server.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text(stringRes(R.string.edit_server)) }
            TextButton(onClick = onDelete) { Text(stringRes(R.string.delete_server)) }
        }
    }
}

@Composable
private fun ServerEditor(form: ServerForm, viewModel: SettingsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = form.name, onValueChange = viewModel::onEditName,
                label = { Text(stringRes(R.string.field_server_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.host, onValueChange = viewModel::onEditHost,
                label = { Text(stringRes(R.string.field_host)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.port, onValueChange = viewModel::onEditPort,
                label = { Text(stringRes(R.string.field_port)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.username, onValueChange = viewModel::onEditUsername,
                label = { Text(stringRes(R.string.field_user)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.workingDir, onValueChange = viewModel::onEditWorkingDir,
                label = { Text(stringRes(R.string.field_working_dir)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::saveServer, modifier = Modifier.weight(1f)) {
                    Text(stringRes(R.string.save_server))
                }
                TextButton(onClick = viewModel::cancelEdit, modifier = Modifier.weight(1f)) {
                    Text(stringRes(R.string.cancel))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPicker(
    servers: List<ServerProfile>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = servers.getOrNull(selectedIndex)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringRes(R.string.server_picker_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            servers.forEachIndexed { i, server ->
                DropdownMenuItem(
                    text = { Text(server.name) },
                    onClick = {
                        onSelect(i)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun stringRes(id: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(id, *args)
