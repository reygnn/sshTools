package com.github.reygnn.prodder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.prodder.R
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.data.ConfigState
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Editor form for one server profile. [index] = null means "new server". */
data class ServerForm(
    val index: Int? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
)

data class SettingsUiState(
    val servers: List<ServerProfile> = emptyList(),
    /** Shared SSH key (PEM) across all profiles; persisted on [SettingsViewModel.done]. */
    val privateKeyPem: String = "",
    /** Non-null while the add/edit editor is open. */
    val editing: ServerForm? = null,
    val saving: Boolean = false,
    val error: UiText? = null,
)

class SettingsViewModel(
    private val settings: SettingsStore,
) : ViewModel() {

    val configState: StateFlow<ConfigState> = settings.isConfigured
        .map { if (it) ConfigState.Configured else ConfigState.Unconfigured }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = ConfigState.Loading)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _savedEvents = Channel<Unit>(capacity = Channel.BUFFERED)
    /** Einmalige Side-Effect-Events für Navigation; Compose collected per LaunchedEffect. */
    val savedEvents: Flow<Unit> = _savedEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    servers = settings.servers.first(),
                    privateKeyPem = settings.readKeyPem() ?: "",
                )
            }
        }
    }

    // ── Shared key ────────────────────────────────────────────────
    fun onPrivateKey(v: String) = _state.update { it.copy(privateKeyPem = v) }

    // ── Server-Editor ─────────────────────────────────────────────
    fun addServer() = _state.update { it.copy(editing = ServerForm(), error = null) }

    fun editServer(index: Int) {
        val p = _state.value.servers.getOrNull(index) ?: return
        _state.update {
            it.copy(
                editing = ServerForm(index, p.name, p.host, p.port.toString(), p.username),
                error = null,
            )
        }
    }

    fun cancelEdit() = _state.update { it.copy(editing = null, error = null) }

    fun onEditName(v: String) = updateForm { it.copy(name = v) }
    fun onEditHost(v: String) = updateForm { it.copy(host = v) }
    fun onEditPort(v: String) = updateForm { it.copy(port = v.filter { c -> c.isDigit() }) }
    fun onEditUsername(v: String) = updateForm { it.copy(username = v) }

    private inline fun updateForm(transform: (ServerForm) -> ServerForm) =
        _state.update { s -> s.editing?.let { s.copy(editing = transform(it)) } ?: s }

    fun saveServer() {
        val f = _state.value.editing ?: return
        if (f.name.isBlank() || f.host.isBlank() || f.username.isBlank()) {
            _state.update { it.copy(error = UiText.Resource(R.string.error_fill_all_fields)) }
            return
        }
        val port = f.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _state.update { it.copy(error = UiText.Resource(R.string.error_invalid_port)) }
            return
        }
        val host = f.host.trim()
        // Den gepinnten Host-Key nur behalten, wenn Endpunkt (host+port)
        // unverändert bleibt; bei Änderung zurücksetzen, damit der neue
        // Endpunkt seinen Key frisch lernt (Name/User ändern den Pin nicht).
        val existing = f.index?.let { _state.value.servers.getOrNull(it) }
        val keepPin = existing != null && existing.host == host && existing.port == port
        val profile = ServerProfile(
            name = f.name.trim(),
            host = host,
            port = port,
            username = f.username.trim(),
            knownHostFingerprint = existing?.knownHostFingerprint?.takeIf { keepPin },
        )
        val list = _state.value.servers.toMutableList()
        if (f.index == null) list.add(profile) else list[f.index] = profile
        viewModelScope.launch {
            settings.saveServers(list)
            _state.update { it.copy(servers = list, editing = null, error = null) }
        }
    }

    fun deleteServer(index: Int) {
        val list = _state.value.servers.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        viewModelScope.launch {
            settings.saveServers(list)
            _state.update { it.copy(servers = list) }
        }
    }

    /**
     * Persist the shared key from the field and leave Settings. Requires at
     * least one server and a non-empty key — otherwise the config would be
     * incomplete and the sessions screen empty.
     */
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
                .onSuccess {
                    _state.update { it.copy(saving = false) }
                    _savedEvents.trySend(Unit)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(saving = false, error = e.message?.let(UiText::Literal)
                            ?: UiText.Resource(R.string.error_unknown))
                    }
                }
        }
    }
}
