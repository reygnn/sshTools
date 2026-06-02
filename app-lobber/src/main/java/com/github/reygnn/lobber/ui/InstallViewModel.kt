package com.github.reygnn.lobber.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.plusCapped
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ui.toUiText
import com.github.reygnn.lobber.BuildConfig
import com.github.reygnn.lobber.ssh.AabEntry
import com.github.reygnn.lobber.ssh.SshClient
import com.github.reygnn.lobber.ssh.SshConfig
import com.github.reygnn.lobber.ssh.SshjClient
import com.github.reygnn.lobber.ssh.resolveConfig
import com.github.reygnn.core.ssh.shellQuote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InstallUiState(
    val configured: Boolean = true,
    val aabs: List<AabEntry> = emptyList(),
    val loading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val installing: String? = null,
    val log: List<LogLine> = emptyList(),
    val lastExitCode: Int? = null,
    val error: UiText? = null,
    val pendingSelfInstall: String? = null,
) {
    val installFinished: Boolean get() = log.any { it is LogLine.ExitCode }
}

data class ServerSelection(
    val servers: List<ServerProfile> = emptyList(),
    val selectedIndex: Int = 0,
)

class InstallViewModel(
    private val settings: SettingsStore,
    private val script: String = "./install-aab.sh",
    private val createClient: (SshConfig) -> SshClient = { SshjClient(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(InstallUiState())
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    val serverSelection: StateFlow<ServerSelection> =
        combine(settings.servers, settings.selectedIndex) { servers, idx ->
            ServerSelection(servers, idx.coerceIn(0, maxOf(0, servers.lastIndex)))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ServerSelection())

    fun selectServer(index: Int) {
        viewModelScope.launch { settings.setSelectedIndex(index); loadAabs() }
    }

    fun loadAabs() {
        if (_state.value.installing != null || _state.value.loading) return
        viewModelScope.launch {
            val config = settings.resolveConfig()
            if (config == null) { _state.update { it.copy(configured = false) }; return@launch }
            _state.update { it.copy(configured = true, loading = true, error = null) }
            runCatching { createClient(config).listAabs() }
                .onSuccess { aabs -> _state.update { it.copy(loading = false, hasLoadedOnce = true, aabs = aabs) } }
                .onFailure { e -> _state.update { it.copy(loading = false, hasLoadedOnce = true, error = e.toUiText()) } }
        }
    }

    fun install(aab: String) {
        viewModelScope.launch {
            val config = settings.resolveConfig() ?: run { _state.update { it.copy(configured = false) }; return@launch }
            val isSelf = runCatching { createClient(config).aabContainsPackage(aab, BuildConfig.APPLICATION_ID) }.getOrDefault(false)
            if (isSelf) { _state.update { it.copy(pendingSelfInstall = aab) }; return@launch }
            startInstall(aab, config)
        }
    }

    fun confirmSelfInstall() {
        val aab = _state.value.pendingSelfInstall ?: return
        _state.update { it.copy(pendingSelfInstall = null) }
        viewModelScope.launch {
            val config = settings.resolveConfig() ?: run { _state.update { it.copy(configured = false) }; return@launch }
            startInstall(aab, config)
        }
    }

    fun cancelSelfInstall() = _state.update { it.copy(pendingSelfInstall = null) }

    private suspend fun startInstall(aab: String, config: SshConfig) {
        _state.update { it.copy(installing = aab, log = emptyList(), lastExitCode = null, error = null) }
        createClient(config).executeStreaming("$script ${shellQuote(aab)}")
            .catch { e -> _state.update { it.copy(installing = null, error = e.toUiText()) } }
            .collect { line -> _state.update { current -> current.copy(log = current.log.plusCapped(line), lastExitCode = if (line is LogLine.ExitCode) line.code else current.lastExitCode) } }
    }

    fun clearAabs() {
        if (_state.value.installing != null) return
        _state.update { it.copy(aabs = emptyList(), hasLoadedOnce = false) }
    }

    fun dismissInstall() = _state.update { it.copy(installing = null, log = emptyList(), lastExitCode = null) }
    fun clearError() = _state.update { it.copy(error = null) }
}
