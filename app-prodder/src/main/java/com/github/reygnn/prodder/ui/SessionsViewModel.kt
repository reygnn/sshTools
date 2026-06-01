package com.github.reygnn.prodder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.prodder.R
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.prodder.ssh.ScreenSession
import com.github.reygnn.prodder.ssh.SshClient
import com.github.reygnn.prodder.ssh.SshConfig
import com.github.reygnn.prodder.ssh.SshjClient
import com.github.reygnn.prodder.ssh.resolveConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val configured: Boolean = true,
    val sessions: List<ScreenSession> = emptyList(),
    val loading: Boolean = false,
    /**
     * True sobald der erste `loadSessions()` zurückkam (egal ob Erfolg oder
     * Fehler). Vorher zeigt die UI den Spinner statt eines Empty-States —
     * sonst flackert beim Cold-Start kurz "keine Sessions".
     */
    val hasLoadedOnce: Boolean = false,
    val error: UiText? = null,
)

/**
 * Server-Auswahl für den Picker im Sessions-Screen. [servers] ist die
 * konfigurierte Liste, [selectedIndex] das aktive Profil. Der Picker bleibt
 * unsichtbar, solange `servers.size <= 1`.
 */
data class ServerSelection(
    val servers: List<ServerProfile> = emptyList(),
    val selectedIndex: Int = 0,
)

/**
 * Listet alle screen-Sessions des gewählten Build-Hosts und erlaubt den
 * Wechsel des Server-Profils. Das Lesen/Senden einer einzelnen Session
 * besorgt [SessionViewModel].
 */
class SessionsViewModel(
    private val settings: SettingsStore,
    createClient: ((SshConfig) -> SshClient)? = null,
) : ViewModel() {

    // Default-Factory pinnt den beim TOFU-Connect gelernten Host-Key. Als
    // Property (nicht Default-Argument) gebaut, damit der Lambda-Body auf
    // viewModelScope/settings zugreifen darf; Tests injizieren ihren Mock.
    private val createClient: (SshConfig) -> SshClient = createClient ?: { cfg ->
        SshjClient(cfg) { fp ->
            viewModelScope.launch { settings.learnHostFingerprint(cfg.host, cfg.port, cfg.username, fp) }
        }
    }

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    val serverSelection: StateFlow<ServerSelection> =
        combine(settings.servers, settings.selectedIndex) { servers, idx ->
            ServerSelection(servers, idx.coerceIn(0, maxOf(0, servers.lastIndex)))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ServerSelection())

    /** Switch the active server profile, then refresh its session list. */
    fun selectServer(index: Int) {
        viewModelScope.launch {
            settings.setSelectedIndex(index)
            loadSessions()
        }
    }

    fun loadSessions() {
        if (_state.value.loading) return
        viewModelScope.launch {
            val config = settings.resolveConfig()
            if (config == null) {
                _state.update { it.copy(configured = false) }
                return@launch
            }
            _state.update { it.copy(configured = true, loading = true, error = null) }
            runCatching { createClient(config).listSessions() }
                .onSuccess { sessions ->
                    _state.update {
                        it.copy(loading = false, hasLoadedOnce = true, sessions = sessions)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            hasLoadedOnce = true,
                            error = e.message?.let(UiText::Literal)
                                ?: UiText.Resource(R.string.error_unknown),
                        )
                    }
                }
        }
    }

    /**
     * Liste leeren, sobald die App in den Hintergrund geht. Beim nächsten
     * Foreground triggert ein `LifecycleResumeEffect` einen frischen
     * `loadSessions()`.
     */
    fun clearSessions() {
        _state.update { it.copy(sessions = emptyList(), hasLoadedOnce = false) }
    }
}
