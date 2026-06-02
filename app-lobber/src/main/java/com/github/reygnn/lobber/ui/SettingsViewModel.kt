package com.github.reygnn.lobber.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.core.data.ConfigState
import com.github.reygnn.core.data.DEFAULT_ADB_HOST
import com.github.reygnn.core.data.ServerForm
import com.github.reygnn.core.data.ServerFormResult
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.data.toForm
import com.github.reygnn.core.data.upsert
import com.github.reygnn.core.data.validate
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.plusCapped
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ui.toUiText
import com.github.reygnn.lobber.R
import com.github.reygnn.lobber.ssh.SshClient
import com.github.reygnn.lobber.ssh.SshConfig
import com.github.reygnn.lobber.ssh.SshjClient
import com.github.reygnn.lobber.ssh.resolveConfig

import com.github.reygnn.core.ssh.shellQuote
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val servers: List<ServerProfile> = emptyList(),
    val privateKeyPem: String = "",
    val editing: ServerForm? = null,
    val saving: Boolean = false,
    val error: UiText? = null,
    val adbHost: String = DEFAULT_ADB_HOST,
    val adbPort: String = "",
    val adbRunning: Boolean = false,
    val adbLog: List<LogLine> = emptyList(),
)

class SettingsViewModel(
    private val settings: SettingsStore,
    private val createClient: (SshConfig) -> SshClient = { SshjClient(it) },
) : ViewModel() {

    val configState: StateFlow<ConfigState> = settings.isConfigured
        .map { if (it) ConfigState.Configured else ConfigState.Unconfigured }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConfigState.Loading)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _savedEvents = Channel<Unit>(capacity = Channel.BUFFERED)
    val savedEvents: Flow<Unit> = _savedEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    servers = settings.servers.first(),
                    privateKeyPem = settings.readKeyPem() ?: "",
                    adbHost = settings.adbHost.first(),
                )
            }
        }
    }

    fun onPrivateKey(v: String) = _state.update { it.copy(privateKeyPem = v) }

    fun addServer() = _state.update { it.copy(editing = ServerForm(), error = null) }

    fun editServer(index: Int) {
        val p = _state.value.servers.getOrNull(index) ?: return
        _state.update { it.copy(editing = p.toForm(index), error = null) }
    }

    fun cancelEdit() = _state.update { it.copy(editing = null, error = null) }
    fun onEditName(v: String) = updateForm { it.copy(name = v) }
    fun onEditHost(v: String) = updateForm { it.copy(host = v) }
    fun onEditPort(v: String) = updateForm { it.copy(port = v.filter { c -> c.isDigit() }) }
    fun onEditUsername(v: String) = updateForm { it.copy(username = v) }
    fun onEditWorkingDir(v: String) = updateForm { it.copy(workingDir = v) }

    private inline fun updateForm(transform: (ServerForm) -> ServerForm) =
        _state.update { s -> s.editing?.let { s.copy(editing = transform(it)) } ?: s }

    fun saveServer() {
        val f = _state.value.editing ?: return
        viewModelScope.launch {
            // Re-read the persisted list first so a host-key pin learned
            // asynchronously (learnHostFingerprint, on the application scope) since
            // the editor was opened isn't clobbered by writing back a stale
            // snapshot — neither the edited profile's own pin nor another
            // profile's. See AUDIT V3.
            val current = settings.servers.first()
            val existing = f.index?.let { current.getOrNull(it) }
            when (val result = f.validate(existing, requireWorkingDir = true)) {
                ServerFormResult.EmptyFields ->
                    _state.update { it.copy(error = UiText.Resource(R.string.error_fill_all_fields)) }
                ServerFormResult.InvalidPort ->
                    _state.update { it.copy(error = UiText.Resource(R.string.error_invalid_port)) }
                is ServerFormResult.Valid -> {
                    val list = current.upsert(f.index, result.profile)
                    settings.saveServers(list)
                    _state.update { it.copy(servers = list, editing = null, error = null) }
                }
            }
        }
    }

    fun deleteServer(index: Int) {
        viewModelScope.launch {
            // Re-read so other profiles' asynchronously-learned pins survive the
            // delete (the whole list is rewritten). See AUDIT V3.
            val list = settings.servers.first().toMutableList()
            if (index !in list.indices) return@launch
            list.removeAt(index)
            settings.saveServers(list)
            _state.update { it.copy(servers = list) }
        }
    }

    fun done() {
        val s = _state.value
        if (s.servers.isEmpty()) {
            _state.update { it.copy(error = UiText.Resource(R.string.error_need_server)) }
            return
        }
        if (s.privateKeyPem.isBlank()) {
            _state.update { it.copy(error = UiText.Resource(R.string.error_key_required)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching { settings.saveKey(s.privateKeyPem) }
                .onSuccess { _state.update { it.copy(saving = false) }; _savedEvents.trySend(Unit) }
                .onFailure { e ->
                    _state.update {
                        it.copy(saving = false, error = e.toUiText())
                    }
                }
        }
    }

    // ── ADB-Reconnect ────────────────────────────────────────────
    fun onAdbHost(v: String) = _state.update { it.copy(adbHost = v) }
    fun onAdbPort(v: String) = _state.update { it.copy(adbPort = v.filter { c -> c.isDigit() }) }
    fun clearAdbLog() = _state.update { it.copy(adbLog = emptyList()) }

    fun reconnectAdb() {
        val s = _state.value
        if (s.adbRunning) return
        val ip = s.adbHost.trim(); val port = s.adbPort.trim()
        if (ip.isBlank() || port.isBlank()) {
            _state.update { it.copy(error = UiText.Resource(R.string.error_adb_fields)) }
            return
        }
        viewModelScope.launch {
            val config = settings.resolveConfig()
            if (config == null) {
                _state.update { it.copy(error = UiText.Resource(R.string.error_not_configured)) }
                return@launch
            }
            settings.saveAdbHost(ip)
            val target = shellQuote("$ip:$port"); val fixed = shellQuote("$ip:5555")
            val cmd = "adb connect $target ; adb tcpip 5555 ; adb connect $fixed ; adb devices -l"
            _state.update { it.copy(adbRunning = true, adbLog = emptyList(), error = null) }
            try {
                createClient(config).executeStreaming(cmd)
                    .catch { e -> _state.update { it.copy(error = e.toUiText()) } }
                    .collect { line -> _state.update { it.copy(adbLog = it.adbLog.plusCapped(line)) } }
            } finally {
                // Always clear the running flag, even if the stream is cancelled
                // mid-flight (AUDIT R3 deferred note).
                _state.update { it.copy(adbRunning = false) }
            }
        }
    }
}
