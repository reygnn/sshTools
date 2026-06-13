package com.github.reygnn.patcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.patcher.ssh.SshConfig
import com.github.reygnn.patcher.ssh.SshjClient
import com.github.reygnn.patcher.ui.OnboardingViewModel
import com.github.reygnn.patcher.ui.SettingsViewModel
import com.github.reygnn.patcher.ui.UpdateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PatcherViewModelFactory(
    private val store: SettingsStore,
    private val appScope: CoroutineScope,
) : ViewModelProvider.Factory {

    // Pins the TOFU-learned host key on appScope so the write survives a VM
    // clear mid-connect (same wiring as Lobber/Caster).
    private fun client(config: SshConfig) =
        SshjClient(config) { fingerprint ->
            appScope.launch { store.learnHostFingerprint(config.host, config.port, fingerprint) }
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        SettingsViewModel::class.java   -> SettingsViewModel(store) as T
        UpdateViewModel::class.java     -> UpdateViewModel(store, ::client) as T
        OnboardingViewModel::class.java -> OnboardingViewModel(store) as T
        else -> error("Unknown ViewModel: ${modelClass.name}")
    }
}
