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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sortierreihenfolge der Projektliste: laufende Projekte zuoberst, innerhalb
 * beider Gruppen alphabetisch (case-insensitive) nach Name. Der Host (`find`)
 * liefert unsortiert, daher wird hier immer neu sortiert.
 */
private fun List<ProjectEntry>.sortedProjects(): List<ProjectEntry> =
    sortedWith(compareByDescending<ProjectEntry> { it.running }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

data class LaunchUiState(
    val configured: Boolean = true,
    val projects: List<ProjectEntry> = emptyList(),
    val loading: Boolean = false,
    /**
     * True sobald der erste `loadProjects()` zurückkam (egal ob Erfolg oder
     * Fehler). Vorher zeigt die UI den Spinner statt eines Empty-States —
     * sonst flackert beim Cold-Start kurz "Keine Projekte gefunden".
     */
    val hasLoadedOnce: Boolean = false,
    /**
     * Projektname solange ein Start-Stream läuft *oder* dessen Log noch
     * sichtbar sein soll. Wird erst durch [dismissLaunch] auf `null` gesetzt —
     * vorher bleibt Log + Exit-Code stehen, damit man ihn lesen kann.
     */
    val launching: String? = null,
    val log: List<LogLine> = emptyList(),
    val lastExitCode: Int? = null,
    val error: UiText? = null,
    /**
     * Projektname, der gerade auf Bestätigung wartet, weil bereits eine
     * Session läuft. Während dieser Zustand aktiv ist, zeigt die UI einen
     * Warndialog ("Session läuft schon — neu starten?") statt sofort zu starten.
     */
    val pendingRestart: String? = null,
    /** Projekt, dessen Stop-Vorgang gerade läuft (für Spinner/Disable im Item). */
    val stopping: String? = null,
) {
    /** True sobald `LogLine.ExitCode` angekommen ist — Hinweis für die UI,
     *  einen Dismiss-Button statt nur den laufenden Stream zu zeigen. */
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
                    // Laufende Projekte zuoberst; innerhalb beider Gruppen
                    // alphabetisch, weil der Host (find) unsortiert liefert.
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
     * Startversuch. Läuft schon eine Session, wird statt zu starten der
     * Bestätigungsdialog ausgelöst (analog zu Lobbers Self-Install-Check).
     */
    fun launch(project: String) {
        viewModelScope.launch {
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

    /** User hat im "läuft schon"-Dialog bestätigt: alte Session beenden, neu starten. */
    fun confirmRestart() {
        val project = _state.value.pendingRestart ?: return
        _state.update { it.copy(pendingRestart = null) }
        viewModelScope.launch {
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
                    // Optimistisch im lokalen State spiegeln; ein folgender
                    // loadProjects() bestätigt es über screen -ls.
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
     * Liste leeren, sobald die App in den Hintergrund geht. Beim nächsten
     * Foreground triggert ein `LifecycleResumeEffect` einen frischen
     * `loadProjects()`. Während ein Start-Stream State trägt, wird nicht
     * geleert.
     */
    fun clearProjects() {
        if (_state.value.launching != null) return
        _state.update { it.copy(projects = emptyList(), hasLoadedOnce = false) }
    }

    /**
     * Schließt die Launch-Progress-View und kehrt zur Projektliste zurück.
     * Triggert anschließend einen Refresh, damit der `running`-Status des
     * gerade gestarteten/neu gestarteten Projekts aktualisiert wird —
     * `LifecycleResumeEffect` feuert hier nicht, weil die Activity nie
     * pausierte.
     */
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
