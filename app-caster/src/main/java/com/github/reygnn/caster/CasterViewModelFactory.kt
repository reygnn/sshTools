package com.github.reygnn.caster

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.caster.ui.LaunchViewModel
import com.github.reygnn.caster.ui.SettingsViewModel

class CasterViewModelFactory(private val store: SettingsStore) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        SettingsViewModel::class.java -> SettingsViewModel(store) as T
        LaunchViewModel::class.java   -> LaunchViewModel(store) as T
        else -> error("Unknown ViewModel: ${modelClass.name}")
    }
}
