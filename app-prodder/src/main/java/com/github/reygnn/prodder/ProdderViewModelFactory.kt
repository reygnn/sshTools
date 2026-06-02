package com.github.reygnn.prodder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.prodder.ssh.SshConfig
import com.github.reygnn.prodder.ssh.SshjClient
import com.github.reygnn.prodder.ui.SessionViewModel
import com.github.reygnn.prodder.ui.SessionsViewModel
import com.github.reygnn.prodder.ui.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ProdderViewModelFactory(
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
        SettingsViewModel::class.java -> SettingsViewModel(store) as T
        SessionsViewModel::class.java -> SessionsViewModel(store, ::client) as T
        SessionViewModel::class.java  -> SessionViewModel(store, ::client) as T
        else -> error("Unknown ViewModel: ${modelClass.name}")
    }
}
