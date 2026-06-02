package com.github.reygnn.prodder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ui.toUiText
import com.github.reygnn.core.data.ServerSelection
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.data.serverSelectionState
import com.github.reygnn.prodder.ssh.ScreenSession
import com.github.reygnn.prodder.ssh.SshClient
import com.github.reygnn.prodder.ssh.SshConfig
import com.github.reygnn.prodder.ssh.SshjClient
import com.github.reygnn.prodder.ssh.resolveConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val configured: Boolean = true,
    val sessions: List<ScreenSession> = emptyList(),
    val loading: Boolean = false,
    /**
     * True once the first `loadSessions()` came back (whether success or
     * error). Before that the UI shows the spinner instead of an empty state —
     * otherwise "no sessions" flickers briefly on cold start.
     */
    val hasLoadedOnce: Boolean = false,
    val error: UiText? = null,
)

/**
 * Lists all screen sessions of the selected build host and allows
 * switching the server profile. Reading/sending an individual session
 * is handled by [SessionViewModel].
 */
class SessionsViewModel(
    private val settings: SettingsStore,
    // Host-key persistence is wired in ProdderViewModelFactory (appScope) so it
    // survives a VM clear; this default is a plain client for tests/standalone.
    private val createClient: (SshConfig) -> SshClient = { SshjClient(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    val serverSelection: StateFlow<ServerSelection> = settings.serverSelectionState(viewModelScope)

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
                            error = e.toUiText(),
                        )
                    }
                }
        }
    }

    /**
     * Clear the list as soon as the app goes into the background. On the next
     * foreground a `LifecycleResumeEffect` triggers a fresh
     * `loadSessions()`.
     */
    fun clearSessions() {
        _state.update { it.copy(sessions = emptyList(), hasLoadedOnce = false) }
    }
}
