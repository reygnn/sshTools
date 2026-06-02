package com.github.reygnn.lobber.ui

import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ui.resolve
import com.github.reygnn.core.ui.KeyField
import com.github.reygnn.core.ui.LogLineRow
import com.github.reygnn.core.ui.ServerEditor
import com.github.reygnn.core.ui.ServerPicker
import com.github.reygnn.core.ui.ServerRow

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.reygnn.lobber.BuildConfig
import com.github.reygnn.lobber.R
import com.github.reygnn.lobber.ssh.AabEntry
import com.github.reygnn.core.ssh.LogLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSaved: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.savedEvents.collect { onSaved() }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = {
                if (onBack != null) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
            },
        )
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

            s.error?.let {
                Text(it.resolve(), color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = viewModel::done,
                enabled = !s.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (s.saving) R.string.saving else R.string.done))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                stringResource(R.string.adb_reconnect_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.adb_reconnect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = s.adbHost, onValueChange = viewModel::onAdbHost,
                label = { Text(stringResource(R.string.field_adb_host)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.adbPort, onValueChange = viewModel::onAdbPort,
                label = { Text(stringResource(R.string.field_adb_port)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::reconnectAdb,
                enabled = !s.adbRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (s.adbRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(if (s.adbRunning) R.string.adb_reconnecting else R.string.adb_reconnect))
            }
            if (s.adbLog.isNotEmpty()) {
                AdbLogCard(log = s.adbLog, onClear = viewModel::clearAdbLog)
            }
        }
    }
}

@Composable
private fun AdbLogCard(log: List<LogLine>, onClear: () -> Unit) {
    // Plain Column (kein LazyColumn): die SettingsScreen scrollt bereits via
    // verticalScroll, und die ADB-Ausgabe ist nur eine Handvoll Zeilen — eine
    // verschachtelte Lazy-Liste mit unbegrenzter Höhe würde hier crashen.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            log.forEach { LogLineRow(it) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.adb_clear_log))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallerScreen(
    viewModel: InstallViewModel,
    onOpenSettings: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val sel by viewModel.serverSelection.collectAsStateWithLifecycle()
    val adbStatus by adbStatusState()
    val context = LocalContext.current
    var adbBlockedAab by remember { mutableStateOf<String?>(null) }

    // Auf jedem ON_RESUME (App-Start, Rückkehr aus dem Hintergrund) AAB-Liste
    // refreshen, damit ein frisch gebauter AAB ohne manuellen Refresh-Tap
    // sichtbar wird. loadAabs() guarded selbst gegen "läuft Install" und
    // "läuft schon ein Refresh".
    LifecycleResumeEffect(Unit) {
        viewModel.loadAabs()
        onPauseOrDispose { }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AdbStatusDot(onClick = { openDeveloperOptions(context) })
                    Text(stringResource(R.string.installer_title, BuildConfig.VERSION_NAME))
                }
            },
            actions = {
                IconButton(onClick = viewModel::loadAabs, enabled = !s.loading && s.installing == null) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_aabs))
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.open_settings))
                }
            },
        )
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Picker nur zeigen, wenn es mehr als ein Profil gibt und gerade
            // kein Install läuft.
            if (sel.servers.size > 1 && s.installing == null) {
                ServerPicker(
                    serverNames = sel.servers.map { it.name },
                    selectedIndex = sel.selectedIndex,
                    onSelect = viewModel::selectServer,
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    s.installing != null -> InstallProgress(
                        aab = s.installing!!,
                        log = s.log,
                        finished = s.installFinished,
                        onDismiss = viewModel::dismissInstall,
                    )
                    !s.hasLoadedOnce -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> AabList(
                        aabs = s.aabs,
                        error = s.error,
                        onInstall = { aab ->
                            if (adbStatus.anyEnabled) viewModel.install(aab)
                            else adbBlockedAab = aab
                        },
                    )
                }
            }
        }
    }

    s.pendingSelfInstall?.let { aab ->
        AlertDialog(
            onDismissRequest = viewModel::cancelSelfInstall,
            title = { Text(stringResource(R.string.self_install_title)) },
            text = { Text(stringResource(R.string.self_install_body, aab)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSelfInstall) {
                    Text(stringResource(R.string.self_install_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelSelfInstall) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (adbBlockedAab != null) {
        AlertDialog(
            onDismissRequest = { adbBlockedAab = null },
            title = { Text(stringResource(R.string.adb_not_ready_title)) },
            text = { Text(stringResource(R.string.adb_not_ready_body)) },
            confirmButton = {
                TextButton(onClick = {
                    openDeveloperOptions(context)
                    adbBlockedAab = null
                }) {
                    Text(stringResource(R.string.adb_not_ready_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { adbBlockedAab = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

// Solange Entwickleroptionen nie freigeschaltet wurden (7× auf Build-Nummer),
// existiert ACTION_APPLICATION_DEVELOPMENT_SETTINGS auf dem Gerät nicht. In dem
// Fall auf die Geräteinfo-Seite ausweichen, wo der User den Schalter freischaltet.
private fun openDeveloperOptions(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        .recoverCatching {
            if (it is ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            } else throw it
        }
}

private val AabDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
private fun AdbStatusDot(onClick: () -> Unit) {
    val status by adbStatusState()
    val color = if (status.anyEnabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val label = stringResource(
        if (status.anyEnabled) R.string.adb_status_active else R.string.adb_status_inactive
    )
    // IconButton gives a Material-spec touch target (≥48dp) around the
    // visually small 10dp dot, plus the standard ripple. Tap = jump to the
    // device's developer options.
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
    }
}

@Composable
private fun AabList(
    aabs: List<AabEntry>,
    error: UiText?,
    onInstall: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        error?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        if (aabs.isEmpty() && error == null) {
            Text(stringResource(R.string.no_aabs_found))
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(aabs) { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name)
                            Text(
                                text = formatAabDate(entry.mtimeEpochSeconds),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { onInstall(entry.name) }) { Text(stringResource(R.string.install)) }
                    }
                }
            }
        }
    }
}

private fun formatAabDate(epochSeconds: Long): String =
    AabDateFormatter.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

@Composable
private fun InstallProgress(
    aab: String,
    log: List<LogLine>,
    finished: Boolean,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.installing_aab, aab), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(log) { line -> LogLineRow(line) }
        }
        if (finished) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onDone: () -> Unit,
    onManual: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.doneEvents.collect { onDone() }
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
                value = s.host, onValueChange = viewModel::onHost,
                label = { Text(stringResource(R.string.field_host)) }, singleLine = true, enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.port, onValueChange = viewModel::onPort,
                label = { Text(stringResource(R.string.field_port)) }, singleLine = true, enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.username, onValueChange = viewModel::onUsername,
                label = { Text(stringResource(R.string.field_user)) }, singleLine = true, enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.password, onValueChange = viewModel::onPassword,
                label = { Text(stringResource(R.string.field_password_once)) }, singleLine = true, enabled = !running,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.workingDir, onValueChange = viewModel::onWorkingDir,
                label = { Text(stringResource(R.string.field_working_dir)) }, singleLine = true, enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )

            s.error?.let {
                Text(it.resolve(), color = MaterialTheme.colorScheme.error)
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
                onClick = viewModel::start,
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
}

@Composable
private fun stepLabel(step: OnboardingStep): String = when (step) {
    OnboardingStep.Idle           -> ""
    OnboardingStep.GeneratingKey  -> stringResource(R.string.step_generating)
    OnboardingStep.PushingKey     -> stringResource(R.string.step_pushing)
    OnboardingStep.Verifying      -> stringResource(R.string.step_verifying)
    OnboardingStep.Saving         -> stringResource(R.string.step_saving)
    OnboardingStep.Done           -> stringResource(R.string.step_done)
}

