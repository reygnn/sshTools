package com.github.reygnn.culler.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.core.data.ServerSelection
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.data.serverSelectionState
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ui.toUiText
import com.github.reygnn.culler.ssh.DirEntry
import com.github.reygnn.culler.ssh.SshClient
import com.github.reygnn.culler.ssh.SshConfig
import com.github.reygnn.culler.ssh.SshjClient
import com.github.reygnn.culler.ssh.resolveConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sort order of the entry list: directories first, then within both groups
 * alphabetically (case-insensitive) by name. The host (`find`) returns them
 * unsorted, so they are always re-sorted here.
 */
private fun List<DirEntry>.sortedEntries(): List<DirEntry> =
    sortedWith(compareByDescending<DirEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

data class ListUiState(
    val configured: Boolean = true,
    val entries: List<DirEntry> = emptyList(),
    val loading: Boolean = false,
    /**
     * True as soon as the first [ListViewModel.loadEntries] returned (whether
     * success or error). Before that the UI shows the spinner instead of an
     * empty state — otherwise "directory is empty" briefly flickers on cold start.
     */
    val hasLoadedOnce: Boolean = false,
    /** Entry currently awaiting delete confirmation; while set, the UI shows the dialog. */
    val pendingDelete: DirEntry? = null,
    /** Name of the entry whose delete is currently running (for spinner/disable in the row). */
    val deleting: String? = null,
    val error: UiText? = null,
)

class ListViewModel(
    private val settings: SettingsStore,
    // Host-key persistence is wired in CullerViewModelFactory (appScope) so it
    // survives a VM clear; this default is a plain client for tests/standalone.
    private val createClient: (SshConfig) -> SshClient = { SshjClient(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(ListUiState())
    val state: StateFlow<ListUiState> = _state.asStateFlow()

    val serverSelection: StateFlow<ServerSelection> = settings.serverSelectionState(viewModelScope)

    /** Switch the active server profile, then refresh the entry list for it. */
    fun selectServer(index: Int) {
        viewModelScope.launch {
            settings.setSelectedIndex(index)
            loadEntries()
        }
    }

    fun loadEntries() {
        if (_state.value.loading) return
        viewModelScope.launch {
            val config = settings.resolveConfig()
            if (config == null) {
                _state.update { it.copy(configured = false) }
                return@launch
            }
            _state.update { it.copy(configured = true, loading = true, error = null) }
            runCatching { createClient(config).listEntries() }
                .onSuccess { entries ->
                    _state.update {
                        it.copy(loading = false, hasLoadedOnce = true, entries = entries.sortedEntries())
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, hasLoadedOnce = true, error = e.toUiText())
                    }
                }
        }
    }

    /** Tap on delete: arm the confirmation dialog (no remote call yet). */
    fun requestDelete(entry: DirEntry) = _state.update { it.copy(pendingDelete = entry, error = null) }

    fun cancelDelete() = _state.update { it.copy(pendingDelete = null) }

    /** User confirmed in the dialog: run the remote delete, then drop the entry locally. */
    fun confirmDelete() {
        val entry = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null, deleting = entry.name, error = null) }
        viewModelScope.launch {
            val config = settings.resolveConfig() ?: run {
                _state.update { it.copy(configured = false, deleting = null) }
                return@launch
            }
            runCatching { createClient(config).deleteEntry(entry.name, entry.isDirectory) }
                .onSuccess {
                    _state.update {
                        it.copy(deleting = null, entries = it.entries.filterNot { e -> e.name == entry.name })
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(deleting = null, error = e.toUiText()) }
                }
        }
    }

    /**
     * Clear the list as soon as the app goes into the background. On the next
     * foreground, a `LifecycleResumeEffect` triggers a fresh [loadEntries].
     */
    fun clearEntries() {
        _state.update { it.copy(entries = emptyList(), hasLoadedOnce = false) }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
