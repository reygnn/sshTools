package com.github.reygnn.lobber

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.lobber.ssh.SshConfig
import com.github.reygnn.lobber.ssh.SshjClient
import com.github.reygnn.lobber.ui.InstallViewModel
import com.github.reygnn.lobber.ui.OnboardingViewModel
import com.github.reygnn.lobber.ui.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class LobberViewModelFactory(
    private val store: SettingsStore,
    private val appScope: CoroutineScope,
) : ViewModelProvider.Factory {

    private fun client(config: SshConfig) =
        SshjClient(config) { fingerprint ->
            appScope.launch { store.learnHostFingerprint(config.host, config.port, fingerprint) }
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        SettingsViewModel::class.java   -> SettingsViewModel(store, ::client) as T
        InstallViewModel::class.java    -> InstallViewModel(store, createClient = ::client) as T
        OnboardingViewModel::class.java -> OnboardingViewModel(store) as T
        else -> error("Unknown ViewModel: ${modelClass.name}")
    }
}
