package com.github.reygnn.prodder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.prodder.ui.SessionViewModel
import com.github.reygnn.prodder.ui.SessionsViewModel
import com.github.reygnn.prodder.ui.SettingsViewModel

class ProdderViewModelFactory(private val store: SettingsStore) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        SettingsViewModel::class.java -> SettingsViewModel(store) as T
        SessionsViewModel::class.java -> SessionsViewModel(store) as T
        SessionViewModel::class.java  -> SessionViewModel(store) as T
        else -> error("Unknown ViewModel: ${modelClass.name}")
    }
}
