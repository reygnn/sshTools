package com.github.reygnn.core.onboarding

import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ssh.SshKeyPair
import com.github.reygnn.core.ssh.SshKeygen
import com.github.reygnn.core.ssh.SshOnboarding
import com.github.reygnn.core.ssh.SshjOnboarding
import kotlinx.coroutines.CoroutineScope
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

/**
 * Typed onboarding error so this Android-free module needn't depend on core-ui's
 * `UiText`; each app maps it to a localized message (mirrors `ServerFormResult`).
 */
sealed interface OnboardingError {
    /** A required field was blank. */
    data object EmptyFields : OnboardingError

    /** An SSH/persistence step failed; [message] is the formatted cause chain. */
    data class Failure(val message: String) : OnboardingError
}

data class OnboardingState(
    val host: String = "", val port: String = "22", val username: String = "",
    val password: String = "", val workingDir: String = "",
    val step: OnboardingStep = OnboardingStep.Idle,
    /**
     * The host-key fingerprint (`SHA256:…`) learned in phase 1, shown to the user
     * for confirmation. Non-null only while [step] is
     * [OnboardingStep.AwaitingHostKeyConfirm]. See AUDIT V4.
     */
    val pendingFingerprint: String? = null,
    val error: OnboardingError? = null,
)

/**
 * App-agnostic, two-phase onboarding state machine shared by Lobber, Caster and
 * Prodder. Generates a keypair and learns the host-key fingerprint **without**
 * sending the password ([start]); only after the user confirms the fingerprint
 * ([confirmHostKey]) is the password transmitted, the public key pushed, pubkey
 * auth verified and everything persisted. See AUDIT V4.
 *
 * Lifecycle-free: it takes a [scope] (the app passes `viewModelScope`) so the
 * logic is testable with a plain test scope and the module stays consistent with
 * the other lifecycle-free core modules. [requireWorkingDir] is the only per-app
 * difference (Prodder has no working dir).
 */
class OnboardingController(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val requireWorkingDir: Boolean,
    private val onboarding: SshOnboarding = SshjOnboarding(),
    private val keygen: () -> SshKeyPair = { SshKeygen.generateEd25519() },
) {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private val _doneEvents = Channel<Unit>(capacity = Channel.BUFFERED)
    val doneEvents: Flow<Unit> = _doneEvents.receiveAsFlow()

    /** Held between phase 1 and 2; never enters the observable state. */
    private var pendingPair: SshKeyPair? = null

    fun onHost(v: String) = _state.update { it.copy(host = v) }
    fun onPort(v: String) = _state.update { it.copy(port = v.filter { c -> c.isDigit() }) }
    fun onUsername(v: String) = _state.update { it.copy(username = v) }
    fun onPassword(v: String) = _state.update { it.copy(password = v) }
    fun onWorkingDir(v: String) = _state.update { it.copy(workingDir = v) }

    /**
     * Phase 1: generate the keypair and learn the host-key fingerprint without
     * sending the password, then pause at [OnboardingStep.AwaitingHostKeyConfirm].
     */
    fun start() {
        val s = _state.value
        // In-flight guard (AUDIT V10).
        if (s.step != OnboardingStep.Idle) return
        if (s.host.isBlank() || s.username.isBlank() || s.password.isBlank() ||
            (requireWorkingDir && s.workingDir.isBlank())
        ) {
            _state.update { it.copy(error = OnboardingError.EmptyFields) }
            return
        }
        val port = s.port.toIntOrNull() ?: 22
        scope.launch {
            runCatching {
                _state.update { it.copy(step = OnboardingStep.GeneratingKey, error = null) }
                pendingPair = keygen()
                _state.update { it.copy(step = OnboardingStep.DiscoveringHost) }
                onboarding.discoverHostKey(s.host.trim(), port)
            }.onSuccess { fingerprint ->
                _state.update {
                    it.copy(step = OnboardingStep.AwaitingHostKeyConfirm, pendingFingerprint = fingerprint)
                }
            }.onFailure { e -> failTo(e) }
        }
    }

    /**
     * Phase 2: the user confirmed the host key. Pin it, send the password, push
     * the public key, verify pubkey auth and persist.
     */
    fun confirmHostKey() {
        val s = _state.value
        if (s.step != OnboardingStep.AwaitingHostKeyConfirm) return
        val fingerprint = s.pendingFingerprint ?: return
        val pair = pendingPair ?: return
        val port = s.port.toIntOrNull() ?: 22
        scope.launch {
            runCatching {
                _state.update { it.copy(step = OnboardingStep.PushingKey, error = null) }
                onboarding.pushPublicKey(
                    host = s.host.trim(), port = port, username = s.username.trim(),
                    password = s.password, publicKeyLine = pair.publicKeyOpenSsh,
                    expectedFingerprint = fingerprint,
                )

                _state.update { it.copy(step = OnboardingStep.Verifying) }
                onboarding.verifyPubkeyAuth(
                    host = s.host.trim(), port = port, username = s.username.trim(),
                    privateKeyPem = pair.privateKeyPem, knownHostFingerprint = fingerprint,
                )

                _state.update { it.copy(step = OnboardingStep.Saving) }
                settings.saveKey(pair.privateKeyPem)
                settings.saveServers(listOf(
                    ServerProfile(
                        name = "Server 1", host = s.host.trim(), port = port,
                        username = s.username.trim(), workingDir = s.workingDir.trim(),
                        knownHostFingerprint = fingerprint,
                    )
                ))
                settings.savePubKey(pair.publicKeyOpenSsh)
            }.onSuccess {
                pendingPair = null
                _state.update { it.copy(step = OnboardingStep.Done, password = "", pendingFingerprint = null) }
                _doneEvents.trySend(Unit)
            }.onFailure { e -> failTo(e) }
        }
    }

    /** The user rejected the host key (or backed out): abort, wiping the password. */
    fun cancelHostKey() {
        if (_state.value.step != OnboardingStep.AwaitingHostKeyConfirm) return
        pendingPair = null
        _state.update { it.copy(step = OnboardingStep.Idle, password = "", pendingFingerprint = null) }
    }

    private fun failTo(e: Throwable) {
        pendingPair = null
        _state.update {
            it.copy(
                step = OnboardingStep.Idle, password = "", pendingFingerprint = null,
                error = OnboardingError.Failure(formatCauseChain(e)),
            )
        }
    }
}

/** Flattens a throwable's cause chain into a readable multi-line message. */
internal fun formatCauseChain(t: Throwable): String = buildString {
    var current: Throwable? = t
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (isNotEmpty()) append("\n→ ")
        append(current::class.simpleName).append(": ").append(current.message ?: "(no message)")
        current = current.cause
    }
}
