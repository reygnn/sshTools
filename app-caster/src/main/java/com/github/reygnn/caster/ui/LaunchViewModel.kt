package com.github.reygnn.caster.ui
import com.github.reygnn.core.ui.UiText

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.caster.R
import com.github.reygnn.core.data.ServerSelection
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.data.serverSelectionState
import com.github.reygnn.core.ssh.LogLine
import com.github.reygnn.core.ssh.chunkedByTime
import com.github.reygnn.core.ssh.plusCapped
import com.github.reygnn.caster.ssh.ProjectEntry
import com.github.reygnn.caster.ssh.SshClient
import com.github.reygnn.caster.ssh.SshConfig
import com.github.reygnn.caster.ssh.SshjClient
import com.github.reygnn.caster.ssh.resolveConfig
import com.github.reygnn.core.ui.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sort order of the project list: running projects on top, within both groups
 * alphabetically (case-insensitive) by name. The host (`find`) returns them
 * unsorted, so they are always re-sorted here.
 */
private fun List<ProjectEntry>.sortedProjects(): List<ProjectEntry> =
    sortedWith(compareByDescending<ProjectEntry> { it.running }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

data class LaunchUiState(
    val configured: Boolean = true,
    val projects: List<ProjectEntry> = emptyList(),
    val loading: Boolean = false,
    /**
     * True as soon as the first `loadProjects()` returned (whether success or
     * error). Before that the UI shows the spinner instead of an empty state —
     * otherwise "No projects found" briefly flickers on cold start.
     */
    val hasLoadedOnce: Boolean = false,
    /**
     * Project name as long as a launch stream is running *or* its log should
     * still be visible. Only set to `null` by [dismissLaunch] — before that the
     * log + exit code remain in place so they can be read.
     */
    val launching: String? = null,
    val log: List<LogLine> = emptyList(),
    val lastExitCode: Int? = null,
    val error: UiText? = null,
    /**
     * Project name currently awaiting confirmation because a session is already
     * running. While this state is active, the UI shows a warning dialog
     * ("Session already running — restart?") instead of starting right away.
     */
    val pendingRestart: String? = null,
    /** Project whose stop operation is currently running (for spinner/disable in the item). */
    val stopping: String? = null,
) {
    /** True as soon as `LogLine.ExitCode` has arrived — a hint for the UI to
     *  show a dismiss button instead of only the running stream. */
    val launchFinished: Boolean
        get() = log.any { it is LogLine.ExitCode }
}

class LaunchViewModel(
    private val settings: SettingsStore,
    // Host-key persistence is wired in CasterViewModelFactory (appScope) so it
    // survives a VM clear; this default is a plain client for tests/standalone.
    private val createClient: (SshConfig) -> SshClient = { SshjClient(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(LaunchUiState())
    val state: StateFlow<LaunchUiState> = _state.asStateFlow()

    val serverSelection: StateFlow<ServerSelection> = settings.serverSelectionState(viewModelScope)

    /** The in-progress streaming launch, so it can be cancelled (AUDIT P1). */
    private var launchJob: Job? = null

    /** Switch the active server profile, then refresh the project list for it. */
    fun selectServer(index: Int) {
        viewModelScope.launch {
            settings.setSelectedIndex(index)
            loadProjects()
        }
    }

    fun loadProjects() {
        if (_state.value.launching != null) return
        if (_state.value.loading) return
        viewModelScope.launch {
            val config = settings.resolveConfig()
            if (config == null) {
                _state.update { it.copy(configured = false) }
                return@launch
            }
            _state.update { it.copy(configured = true, loading = true, error = null) }
            runCatching { createClient(config).listProjects() }
                .onSuccess { projects ->
                    // Running projects on top; within both groups
                    // alphabetically, because the host (find) returns them unsorted.
                    _state.update {
                        it.copy(loading = false, hasLoadedOnce = true, projects = projects.sortedProjects())
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
     * Launch attempt. If a session is already running, the confirmation dialog
     * is triggered instead of starting (analogous to Lobber's self-install check).
     */
    fun launch(project: String) {
        launchJob = viewModelScope.launch {
            val config = settings.resolveConfig() ?: run {
                _state.update { it.copy(configured = false) }
                return@launch
            }
            val alreadyRunning = runCatching {
                createClient(config).isSessionRunning(project)
            }.getOrDefault(false)
            if (alreadyRunning) {
                _state.update { it.copy(pendingRestart = project) }
                return@launch
            }
            startLaunch(project, config)
        }
    }

    /** User confirmed in the "already running" dialog: stop the old session, restart. */
    fun confirmRestart() {
        val project = _state.value.pendingRestart ?: return
        _state.update { it.copy(pendingRestart = null) }
        launchJob = viewModelScope.launch {
            val config = settings.resolveConfig() ?: run {
                _state.update { it.copy(configured = false) }
                return@launch
            }
            runCatching { createClient(config).stopSession(project) }
            startLaunch(project, config)
        }
    }

    fun cancelRestart() {
        _state.update { it.copy(pendingRestart = null) }
    }

    fun stop(project: String) {
        viewModelScope.launch {
            val config = settings.resolveConfig() ?: run {
                _state.update { it.copy(configured = false) }
                return@launch
            }
            _state.update { it.copy(stopping = project, error = null) }
            val ok = runCatching { createClient(config).stopSession(project) }
                .getOrDefault(false)
            _state.update { current ->
                current.copy(
                    stopping = null,
                    // Mirror optimistically in the local state; a subsequent
                    // loadProjects() confirms it via screen -ls.
                    projects = if (ok) {
                        current.projects
                            .map {
                                if (it.name == project) it.copy(running = false) else it
                            }
                            .sortedProjects()
                    } else {
                        current.projects
                    },
                    error = if (ok) null else UiText.Resource(R.string.error_stop_failed),
                )
            }
        }
    }

    private suspend fun startLaunch(project: String, config: SshConfig) {
        _state.update {
            it.copy(launching = project, log = emptyList(), lastExitCode = null, error = null)
        }
        createClient(config)
            .startStreaming(project)
            // Coalesce per-line emissions into ~50ms batches so a chatty launch log
            // updates state per batch, not per line (bounds recomposition rate).
            .chunkedByTime()
            .catch { e ->
                _state.update {
                    it.copy(
                        launching = null,
                        error = e.toUiText(),
                    )
                }
            }
            .collect { batch ->
                val exit = batch.lastOrNull { it is LogLine.ExitCode } as LogLine.ExitCode?
                _state.update { current ->
                    current.copy(
                        log = current.log.plusCapped(batch),
                        lastExitCode = if (exit != null) exit.code else current.lastExitCode,
                    )
                }
            }
    }

    /**
     * Clear the list as soon as the app goes into the background. On the next
     * foreground, a `LifecycleResumeEffect` triggers a fresh `loadProjects()`.
     * While a launch stream carries state, nothing is cleared.
     */
    fun clearProjects() {
        if (_state.value.launching != null) return
        _state.update { it.copy(projects = emptyList(), hasLoadedOnce = false) }
    }

    /**
     * Closes the launch progress view and returns to the project list.
     * Then triggers a refresh so the `running` status of the
     * just-started/restarted project is updated —
     * `LifecycleResumeEffect` does not fire here, because the activity never
     * paused.
     */
    /**
     * Cancel an in-progress launch. The streaming command has no read timeout, so
     * this is the only recovery from a stalled stream: cancelling the collect job
     * closes the channel → blocked readers get EOF → the connection is torn down
     * (V5 teardown). See AUDIT P1.
     */
    fun cancelLaunch() {
        if (_state.value.launching == null) return
        launchJob?.cancel()
        _state.update { it.copy(launching = null, log = emptyList(), lastExitCode = null) }
    }

    fun dismissLaunch() {
        _state.update {
            it.copy(launching = null, log = emptyList(), lastExitCode = null)
        }
        loadProjects()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
