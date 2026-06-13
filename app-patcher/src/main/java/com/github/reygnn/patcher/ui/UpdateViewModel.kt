package com.github.reygnn.patcher.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.patcher.R
import com.github.reygnn.patcher.ssh.ScreenMissingException
import com.github.reygnn.patcher.ssh.SshClient
import com.github.reygnn.patcher.ssh.SshConfig
import com.github.reygnn.patcher.ssh.SshjClient
import com.github.reygnn.patcher.ssh.UpdateStatus
import com.github.reygnn.patcher.ssh.resolveConfig
import com.github.reygnn.core.data.ServerSelection
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.data.serverSelectionState
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.chunkedByTime
import com.github.reygnn.core.ssh.plusCapped
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.core.ui.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the host (and thus the UI) stands with respect to an apt run. */
enum class UpdatePhase {
    /** Nothing running, nothing pending — offer to start an update. */
    Idle,

    /** A detached update `screen` session is alive on the host. */
    Running,

    /** A run has completed; show its result (and a reboot offer if pending). */
    Finished,
}

data class UpdateUiState(
    val configured: Boolean = true,
    /** A status poll is in flight and nothing has loaded yet (cold spinner). */
    val loading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val phase: UpdatePhase = UpdatePhase.Idle,
    /** Actively collecting the log stream right now. */
    val watching: Boolean = false,
    val log: List<LogLine> = emptyList(),
    /** apt's exit code of the last completed run (`null` until one finishes). */
    val aptExitCode: Int? = null,
    val rebootRequired: Boolean = false,
    val rebootPackages: List<String> = emptyList(),
    /** The reboot confirmation dialog is showing. */
    val pendingReboot: Boolean = false,
    val rebooting: Boolean = false,
    val error: UiText? = null,
    /** A neutral one-shot notice (e.g. "reboot dispatched"), shown until next action. */
    val info: UiText? = null,
) {
    /** The last completed run succeeded (apt returned 0). */
    val succeeded: Boolean get() = aptExitCode == 0
}

/**
 * Drives the apt-update screen. The update itself runs detached in a `screen`
 * session on the host (see [SshClient]); this ViewModel polls that state, attaches
 * to the live log when one is running, and — once a run finishes — surfaces apt's
 * result and a reboot offer when the host flags `/var/run/reboot-required`.
 *
 * Because the run is detached, a dropped connection never aborts apt: the stream
 * just errors out (recoverable with [resumeWatching]) while dpkg keeps going.
 */
class UpdateViewModel(
    private val settings: SettingsStore,
    // Host-key persistence is wired in PatcherViewModelFactory (appScope) so it
    // survives a VM clear; this default is a plain client for tests/standalone.
    private val createClient: (SshConfig) -> SshClient = { SshjClient(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val serverSelection: StateFlow<ServerSelection> = settings.serverSelectionState(viewModelScope)

    /** The active log-stream collection, so it can be cancelled (background/stop). */
    private var watchJob: Job? = null

    /** Switch the active server profile, then re-poll its update state. */
    fun selectServer(index: Int) {
        viewModelScope.launch {
            settings.setSelectedIndex(index)
            refresh()
        }
    }

    /**
     * Poll the host's update state and react: attach to a live run, replay a
     * just-finished log, or settle on idle. Skipped while already watching — the
     * live stream already reflects the state.
     */
    fun refresh() {
        if (_state.value.watching) return
        viewModelScope.launch {
            val config = settings.resolveConfig() ?: run {
                _state.update { it.copy(configured = false) }
                return@launch
            }
            _state.update { it.copy(configured = true, loading = true, error = null) }
            runCatching { createClient(config).status() }
                .onSuccess { st ->
                    applyStatus(st)
                    maybeAttach(st)
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, hasLoadedOnce = true, error = e.toUiText()) }
                }
        }
    }

    /** Start a fresh update, then watch its log. No-op if one is already running. */
    fun startUpdate() {
        if (_state.value.phase == UpdatePhase.Running || _state.value.watching) return
        viewModelScope.launch {
            val config = settings.resolveConfig() ?: run {
                _state.update { it.copy(configured = false) }
                return@launch
            }
            _state.update { it.copy(phase = UpdatePhase.Running, error = null, info = null, aptExitCode = null) }
            runCatching { createClient(config).startUpdate() }
                .onFailure { e ->
                    val msg = if (e is ScreenMissingException) {
                        UiText.Resource(R.string.error_screen_missing)
                    } else {
                        e.toUiText()
                    }
                    _state.update { it.copy(phase = UpdatePhase.Idle, error = msg) }
                    return@launch
                }
            startWatching()
        }
    }

    /** Re-attach to a running update after the stream was stopped or dropped. */
    fun resumeWatching() = startWatching()

    /**
     * Stop collecting the log without touching the update — apt keeps running
     * detached. The screen falls back to "running on host" with a resume option.
     */
    fun stopWatching() {
        watchJob?.cancel()
        _state.update { it.copy(watching = false) }
    }

    fun requestReboot() = _state.update { it.copy(pendingReboot = true) }
    fun cancelReboot() = _state.update { it.copy(pendingReboot = false) }

    fun confirmReboot() {
        _state.update { it.copy(pendingReboot = false, rebooting = true, error = null) }
        viewModelScope.launch {
            val config = settings.resolveConfig() ?: run {
                _state.update { it.copy(configured = false, rebooting = false) }
                return@launch
            }
            val ok = runCatching { createClient(config).reboot() }.getOrDefault(false)
            _state.update {
                if (ok) it.copy(
                    rebooting = false,
                    rebootRequired = false,
                    rebootPackages = emptyList(),
                    info = UiText.Resource(R.string.reboot_dispatched),
                ) else it.copy(rebooting = false, error = UiText.Resource(R.string.error_reboot_failed))
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Cancel the live stream when the app backgrounds; foreground re-attaches. */
    fun onBackground() {
        watchJob?.cancel()
        _state.update { it.copy(watching = false) }
    }

    private fun applyStatus(st: UpdateStatus) {
        _state.update {
            it.copy(
                loading = false,
                hasLoadedOnce = true,
                phase = when {
                    st.running -> UpdatePhase.Running
                    st.finished -> UpdatePhase.Finished
                    else -> UpdatePhase.Idle
                },
                aptExitCode = st.aptExitCode,
                rebootRequired = st.rebootRequired,
                rebootPackages = st.rebootPackages,
            )
        }
    }

    /**
     * Attach to the log when a run is live, or replay a just-finished log once (to
     * populate the view on a cold open). The host-side poll loop re-emits the log
     * from the top, so the stream is always started against an empty [log].
     */
    private fun maybeAttach(st: UpdateStatus) {
        val finishedWithoutLog = st.finished && _state.value.log.isEmpty()
        if ((st.running || finishedWithoutLog) && watchJob?.isActive != true) startWatching()
    }

    private fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            val config = settings.resolveConfig() ?: run {
                _state.update { it.copy(configured = false) }
                return@launch
            }
            // The poll loop replays the whole log from line 1, so start clean.
            _state.update { it.copy(watching = true, log = emptyList(), error = null) }
            var streamError = false
            createClient(config).streamLog()
                .chunkedByTime()
                .catch { e ->
                    streamError = true
                    // apt keeps running detached; only the view stopped.
                    _state.update { it.copy(watching = false, error = e.toUiText()) }
                }
                .collect { batch ->
                    _state.update { it.copy(log = it.log.plusCapped(batch)) }
                }
            if (!streamError) {
                // Clean end = the done-sentinel was reached. Re-poll for the
                // authoritative final state (apt exit code, reboot-required).
                _state.update { it.copy(watching = false) }
                refresh()
            }
        }
    }
}
