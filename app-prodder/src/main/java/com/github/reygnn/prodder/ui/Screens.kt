package com.github.reygnn.prodder.ui

import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ui.resolve
import com.github.reygnn.core.ui.KeyField
import com.github.reygnn.core.ui.ServerEditor
import com.github.reygnn.core.ui.ServerPicker
import com.github.reygnn.core.ui.ServerRow
import com.github.reygnn.core.ui.StatusDot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.reygnn.prodder.R
import com.github.reygnn.prodder.ssh.ScreenSession
import kotlinx.coroutines.delay

/* ------------------------------------------------------------------ */
/* Sessions: alle screen-Sessions des gewählten Hosts                  */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    versionName: String,
    onOpenSettings: () -> Unit,
    onOpenSession: (id: String, name: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sel by viewModel.serverSelection.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sessions_title, versionName)) },
                actions = {
                    IconButton(onClick = viewModel::loadSessions) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_sessions))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.open_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (sel.servers.size > 1) {
                ServerPicker(
                    serverNames = sel.servers.map { it.name },
                    selectedIndex = sel.selectedIndex,
                    onSelect = viewModel::selectServer,
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    !state.hasLoadedOnce && state.loading ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.error != null && state.sessions.isEmpty() ->
                        Text(
                            state.error!!.resolve(),
                            Modifier.align(Alignment.Center).padding(24.dp),
                            color = MaterialTheme.colorScheme.error,
                        )

                    state.hasLoadedOnce && state.sessions.isEmpty() ->
                        Text(
                            stringResource(R.string.no_sessions_found),
                            Modifier.align(Alignment.Center).padding(24.dp),
                        )

                    else -> SessionList(sessions = state.sessions, onOpen = onOpenSession)
                }
            }
        }
    }
}

@Composable
private fun SessionList(
    sessions: List<ScreenSession>,
    onOpen: (id: String, name: String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(sessions, key = { it.id }) { session ->
            SessionRow(session = session, onClick = { onOpen(session.id, session.name) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun SessionRow(session: ScreenSession, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(active = session.attached)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(session.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (session.attached) R.string.status_attached else R.string.status_detached),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            session.id,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/* ------------------------------------------------------------------ */
/* Session-Detail: hardcopy-Snapshot + Eingabe via stuff               */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Auto-Refresh wird hier in der UI getrieben (nicht im ViewModel), an
    // state.autoRefresh gekoppelt: schaltet der User ihn aus, endet die
    // Schleife; verlässt er den Screen, wird der LaunchedEffect gecancelt.
    LaunchedEffect(state.autoRefresh, state.sessionId) {
        while (state.autoRefresh && state.sessionId.isNotEmpty()) {
            delay(2000)
            viewModel.refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.sessionName) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
                actions = {
                    FilterChip(
                        selected = state.autoRefresh,
                        onClick = viewModel::toggleAutoRefresh,
                        label = { Text(stringResource(R.string.auto_refresh)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_screen))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            ScreenSnapshot(
                text = state.screen,
                loadingFirst = !state.hasLoadedOnce && state.loading,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            state.error?.let { err ->
                Text(
                    err.resolve(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            QuickKeys(enabled = !state.sending, viewModel = viewModel)
            Spacer(Modifier.size(8.dp))
            InputRow(enabled = !state.sending, onSend = { viewModel.send(it) })
        }
    }
}

@Composable
private fun ScreenSnapshot(text: String, loadingFirst: Boolean, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            if (loadingFirst) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                val vScroll = rememberScrollState()
                // Beim Aktualisieren ans Ende scrollen — der Prompt steht unten.
                LaunchedEffect(text) { vScroll.scrollTo(vScroll.maxValue) }
                Column(Modifier.fillMaxSize().verticalScroll(vScroll).padding(12.dp)) {
                    Text(
                        text = text.ifEmpty { stringResource(R.string.empty_screen) },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickKeys(enabled: Boolean, viewModel: SessionViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = viewModel::sendEnter,
            enabled = enabled,
            label = { Text(stringResource(R.string.key_enter)) },
        )
        AssistChip(
            onClick = { viewModel.send("y") },
            enabled = enabled,
            label = { Text("y") },
        )
        AssistChip(
            onClick = { viewModel.send("n") },
            enabled = enabled,
            label = { Text("n") },
        )
        AssistChip(
            onClick = viewModel::sendCtrlC,
            enabled = enabled,
            label = { Text(stringResource(R.string.key_ctrl_c)) },
        )
    }
}

@Composable
private fun InputRow(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.input_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            enabled = enabled && text.isNotEmpty(),
            onClick = {
                onSend(text)
                text = ""
            },
        ) { Text(stringResource(R.string.send)) }
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
                ServerEditor(
                    name = form.name,
                    host = form.host,
                    port = form.port,
                    username = form.username,
                    // Prodder profiles have no working dir.
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

