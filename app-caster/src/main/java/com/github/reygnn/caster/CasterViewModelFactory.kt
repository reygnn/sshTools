package com.github.reygnn.caster

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.caster.ssh.SshConfig
import com.github.reygnn.caster.ssh.SshjClient
import com.github.reygnn.caster.ui.LaunchViewModel
import com.github.reygnn.caster.ui.OnboardingViewModel
import com.github.reygnn.caster.ui.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class CasterViewModelFactory(
    private val store: SettingsStore,
    private val appScope: CoroutineScope,
) : ViewModelProvider.Factory {

    // Pins the TOFU-learned host key on appScope so the write survives a VM
    // clear mid-connect (same wiring as Lobber/Prodder).
    private fun client(config: SshConfig) =
        SshjClient(config) { fingerprint ->
            appScope.launch { store.learnHostFingerprint(config.host, config.port, fingerprint) }
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        SettingsViewModel::class.java   -> SettingsViewModel(store) as T
        LaunchViewModel::class.java     -> LaunchViewModel(store, ::client) as T
        OnboardingViewModel::class.java -> OnboardingViewModel(store) as T
        else -> error("Unknown ViewModel: ${modelClass.name}")
    }
}
