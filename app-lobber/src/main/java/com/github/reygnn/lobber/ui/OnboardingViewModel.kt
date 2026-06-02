package com.github.reygnn.lobber.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ssh.SshKeyPair
import com.github.reygnn.core.ssh.SshKeygen
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.lobber.R
import com.github.reygnn.lobber.ssh.SshBootstrap
import com.github.reygnn.lobber.ssh.SshConfig
import com.github.reygnn.lobber.ssh.SshjBootstrap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep {
    Idle, GeneratingKey, DiscoveringHost, AwaitingHostKeyConfirm,
    PushingKey, Verifying, Saving, Done,
}

data class OnboardingUiState(
    val host: String = "", val port: String = "22", val username: String = "",
    val password: String = "", val workingDir: String = "",
    val step: OnboardingStep = OnboardingStep.Idle,
    /**
     * The host-key fingerprint (`SHA256:…`) learned in phase 1, shown to the user
     * for confirmation. Non-null only while [step] is
     * [OnboardingStep.AwaitingHostKeyConfirm]. See AUDIT V4.
     */
    val pendingFingerprint: String? = null,
    val error: UiText? = null,
)

class OnboardingViewModel(
    private val settings: SettingsStore,
    private val bootstrap: SshBootstrap = SshjBootstrap(),
    private val keygen: () -> SshKeyPair = { SshKeygen.generateEd25519() },
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _doneEvents = Channel<Unit>(capacity = Channel.BUFFERED)
    val doneEvents: Flow<Unit> = _doneEvents.receiveAsFlow()

    /**
     * The keypair generated in phase 1, held until the user confirms the host key
     * in phase 2. Kept out of [OnboardingUiState] so the private key never enters
     * the observable UI state.
     */
    private var pendingPair: SshKeyPair? = null

    fun onHost(v: String) = _state.update { it.copy(host = v) }
    fun onPort(v: String) = _state.update { it.copy(port = v.filter { c -> c.isDigit() }) }
    fun onUsername(v: String) = _state.update { it.copy(username = v) }
    fun onPassword(v: String) = _state.update { it.copy(password = v) }
    fun onWorkingDir(v: String) = _state.update { it.copy(workingDir = v) }

    /**
     * Phase 1: generate the keypair and learn the host-key fingerprint **without**
     * sending the password, then pause at [OnboardingStep.AwaitingHostKeyConfirm]
     * so the user can verify the fingerprint. The password is only transmitted in
     * [confirmHostKey]. See AUDIT V4.
     */
    fun start() {
        val s = _state.value
        // In-flight guard: a second tap in the window before `step` flips would
        // otherwise launch a second pipeline. See AUDIT V10.
        if (s.step != OnboardingStep.Idle) return
        if (s.host.isBlank() || s.username.isBlank() || s.password.isBlank() || s.workingDir.isBlank()) {
            _state.update { it.copy(error = UiText.Resource(R.string.error_fill_all_fields)) }
            return
        }
        val port = s.port.toIntOrNull() ?: 22
        viewModelScope.launch {
            runCatching {
                _state.update { it.copy(step = OnboardingStep.GeneratingKey, error = null) }
                pendingPair = keygen()

                _state.update { it.copy(step = OnboardingStep.DiscoveringHost) }
                bootstrap.discoverHostKey(s.host.trim(), port)
            }.onSuccess { fingerprint ->
                _state.update {
                    it.copy(step = OnboardingStep.AwaitingHostKeyConfirm, pendingFingerprint = fingerprint)
                }
            }.onFailure { e ->
                pendingPair = null
                Log.e("Lobber/Onboarding", "failed at ${_state.value.step}", e)
                _state.update {
                    it.copy(step = OnboardingStep.Idle, password = "", pendingFingerprint = null, error = UiText.Literal(formatCauseChain(e)))
                }
            }
        }
    }

    /**
     * Phase 2: the user confirmed the host key shown by [start]. Pin it, then send
     * the password, push the public key, verify pubkey auth and persist.
     */
    fun confirmHostKey() {
        val s = _state.value
        if (s.step != OnboardingStep.AwaitingHostKeyConfirm) return
        val fingerprint = s.pendingFingerprint ?: return
        val pair = pendingPair ?: return
        val port = s.port.toIntOrNull() ?: 22
        viewModelScope.launch {
            runCatching {
                _state.update { it.copy(step = OnboardingStep.PushingKey, error = null) }
                bootstrap.pushPublicKey(
                    host = s.host.trim(), port = port, username = s.username.trim(),
                    password = s.password, publicKeyLine = pair.publicKeyOpenSsh,
                    expectedFingerprint = fingerprint,
                )

                val cfg = SshConfig(
                    host = s.host.trim(), port = port, username = s.username.trim(),
                    workingDir = s.workingDir.trim(), privateKeyPem = pair.privateKeyPem,
                    knownHostFingerprint = fingerprint,
                )

                _state.update { it.copy(step = OnboardingStep.Verifying) }
                bootstrap.verifyPubkeyAuth(cfg)

                _state.update { it.copy(step = OnboardingStep.Saving) }
                settings.saveKey(pair.privateKeyPem)
                settings.saveServers(listOf(
                    ServerProfile(
                        name = "Server 1", host = cfg.host, port = cfg.port,
                        username = cfg.username, workingDir = cfg.workingDir,
                        knownHostFingerprint = fingerprint,
                    )
                ))
                settings.savePubKey(pair.publicKeyOpenSsh)
            }.onSuccess {
                pendingPair = null
                _state.update { it.copy(step = OnboardingStep.Done, password = "", pendingFingerprint = null) }
                _doneEvents.trySend(Unit)
            }.onFailure { e ->
                pendingPair = null
                Log.e("Lobber/Onboarding", "failed at ${_state.value.step}", e)
                _state.update {
                    it.copy(step = OnboardingStep.Idle, password = "", pendingFingerprint = null, error = UiText.Literal(formatCauseChain(e)))
                }
            }
        }
    }

    /**
     * The user rejected the host key (or backed out). Abort onboarding without
     * sending the password and clear it from memory.
     */
    fun cancelHostKey() {
        if (_state.value.step != OnboardingStep.AwaitingHostKeyConfirm) return
        pendingPair = null
        _state.update { it.copy(step = OnboardingStep.Idle, password = "", pendingFingerprint = null) }
    }

    private fun formatCauseChain(t: Throwable): String = buildString {
        var current: Throwable? = t
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (isNotEmpty()) append("\n→ ")
            append(current::class.simpleName).append(": ").append(current.message ?: "(no message)")
            current = current.cause
        }
    }
}
