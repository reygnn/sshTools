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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ETX (0x03 = Ctrl-C) als sendbarer Steuerzeichen-Payload. */
private const val CTRL_C = "\u0003"

data class SessionUiState(
    val sessionId: String = "",
    val sessionName: String = "",
    /** Letzter `hardcopy`-Snapshot des Schirms (VT-aufgelöster Klartext). */
    val screen: String = "",
    /** True während ein capture läuft (für einen dezenten Lade-Indikator). */
    val loading: Boolean = false,
    /** Steuert, ob die UI periodisch `refresh()` aufruft. */
    val autoRefresh: Boolean = true,
    /** True während ein `stuff` unterwegs ist. */
    val sending: Boolean = false,
    /** True sobald der erste capture zurückkam — vorher Spinner statt Leerschirm. */
    val hasLoadedOnce: Boolean = false,
    val error: UiText? = null,
)

/**
 * Liest und bespielt **eine** screen-Session. Bewusst ohne eigene
 * Poll-Schleife: das periodische Nachladen treibt die UI per
 * `LaunchedEffect` (an [SessionUiState.autoRefresh] gekoppelt), sodass dieser
 * ViewModel frei von langlebigen Coroutinen und damit unter
 * `runTest(rule.dispatcher)` ohne Endlosschleife testbar bleibt.
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
     * Bindet die View an Session [id]/[name] und holt sofort einen ersten
     * Snapshot. Idempotent für dieselbe ID (verhindert Reset beim Recompose).
     */
    fun bind(id: String, name: String) {
        if (_state.value.sessionId == id) return
        _state.value = SessionUiState(sessionId = id, sessionName = name)
        refresh()
    }

    fun toggleAutoRefresh() = _state.update { it.copy(autoRefresh = !it.autoRefresh) }

    /** Einen Snapshot des aktuellen Schirms holen. */
    fun refresh() {
        val id = _state.value.sessionId
        if (id.isEmpty()) return
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

    /** Freitext senden; [appendEnter] hängt ein Enter (CR) an. */
    fun send(text: String, appendEnter: Boolean = true) =
        sendRaw(buildStuffPayload(text, appendEnter))

    /** Nur Enter (CR) — nimmt z. B. den Default eines `[1/2]`-Prompts an. */
    fun sendEnter() = sendRaw(buildStuffPayload("", appendEnter = true))

    /** Ctrl-C in die Session schicken (laufenden Vordergrundprozess unterbrechen). */
    fun sendCtrlC() = sendRaw(CTRL_C)

    private fun sendRaw(payload: String) {
        val id = _state.value.sessionId
        if (id.isEmpty()) return
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
            // Nach dem Senden den Schirm aktualisieren, damit der Effekt der
            // Eingabe sichtbar wird — unabhängig vom Auto-Refresh-Intervall.
            if (ok) refresh()
        }
    }
}
