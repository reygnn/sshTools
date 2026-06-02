package com.github.reygnn.prodder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.prodder.R
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ui.toUiText
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.prodder.ssh.SshClient
import com.github.reygnn.prodder.ssh.SshConfig
import com.github.reygnn.prodder.ssh.SshjClient
import com.github.reygnn.prodder.ssh.resolveConfig
import com.github.reygnn.prodder.ssh.buildStuffPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ETX (0x03 = Ctrl-C) as a sendable control-character payload. */
private const val CTRL_C = "\u0003"

data class SessionUiState(
    val sessionId: String = "",
    val sessionName: String = "",
    /** Last `hardcopy` snapshot of the screen (VT-resolved plain text). */
    val screen: String = "",
    /** True while a capture is running (for a subtle loading indicator). */
    val loading: Boolean = false,
    /** Controls whether the UI periodically calls `refresh()`. */
    val autoRefresh: Boolean = true,
    /** True while a `stuff` is in flight. */
    val sending: Boolean = false,
    /** True once the first capture came back — before that a spinner instead of an empty screen. */
    val hasLoadedOnce: Boolean = false,
    val error: UiText? = null,
)

/**
 * Reads and feeds **one** screen session. Deliberately without its own
 * poll loop: the periodic reload is driven by the UI via
 * `LaunchedEffect` (coupled to [SessionUiState.autoRefresh]), so that this
 * ViewModel stays free of long-lived coroutines and thus remains testable
 * under `runTest(rule.dispatcher)` without an endless loop.
 */
class SessionViewModel(
    private val settings: SettingsStore,
    // Host-key persistence is wired in ProdderViewModelFactory (appScope) so it
    // survives a VM clear; this default is a plain client for tests/standalone.
    private val createClient: (SshConfig) -> SshClient = { SshjClient(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    /**
     * Binds the view to session [id]/[name] and immediately fetches a first
     * snapshot. Idempotent for the same ID (prevents a reset on recompose).
     */
    fun bind(id: String, name: String) {
        if (_state.value.sessionId == id) return
        _state.value = SessionUiState(sessionId = id, sessionName = name)
        refresh()
    }

    fun toggleAutoRefresh() = _state.update { it.copy(autoRefresh = !it.autoRefresh) }

    /** Fetch a snapshot of the current screen. */
    fun refresh() {
        val id = _state.value.sessionId
        if (id.isEmpty()) return
        // No second capture while one is running — the auto-refresh tick (2 s),
        // the refresh button and the sendRaw follow-up could otherwise overlap
        // and write back a stale snapshot (last-writer-wins).
        if (_state.value.loading) return
        viewModelScope.launch {
            val config = settings.resolveConfig() ?: return@launch
            _state.update { it.copy(loading = true) }
            runCatching { createClient(config).capture(id) }
                .onSuccess { text ->
                    _state.update {
                        it.copy(loading = false, hasLoadedOnce = true, screen = text, error = null)
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

    /** Send free text; [appendEnter] appends an Enter (CR). */
    fun send(text: String, appendEnter: Boolean = true) =
        sendRaw(buildStuffPayload(text, appendEnter))

    /** Just Enter (CR) — accepts e.g. the default of a `[1/2]` prompt. */
    fun sendEnter() = sendRaw(buildStuffPayload("", appendEnter = true))

    /** Send Ctrl-C into the session (interrupt the running foreground process). */
    fun sendCtrlC() = sendRaw(CTRL_C)

    private fun sendRaw(payload: String) {
        val id = _state.value.sessionId
        if (id.isEmpty()) return
        // No second `stuff` while one is in flight — the QuickKeys chips
        // and the send button are separate widgets, so fast taps could otherwise
        // send two `stuff` payloads interleaved and out-of-order. The UI `enabled`
        // alone is not enough (race before the state flip). See AUDIT V7.
        if (_state.value.sending) return
        viewModelScope.launch {
            val config = settings.resolveConfig() ?: return@launch
            _state.update { it.copy(sending = true, error = null) }
            val ok = runCatching { createClient(config).sendInput(id, payload) }
                .getOrDefault(false)
            _state.update {
                it.copy(
                    sending = false,
                    error = if (ok) it.error else UiText.Resource(R.string.error_send_failed),
                )
            }
            // After sending, refresh the screen so the effect of the
            // input becomes visible — independent of the auto-refresh interval.
            if (ok) refresh()
        }
    }
}
