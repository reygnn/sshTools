package com.github.reygnn.culler.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.onboarding.OnboardingController

/**
 * Thin lifecycle wrapper around the shared [OnboardingController] (core-onboarding).
 * All onboarding logic is shared with Lobber/Caster/Prodder; Culler manages a
 * directory on the host, so a working dir (the managed directory) is required.
 */
class OnboardingViewModel(settings: SettingsStore) : ViewModel() {
    val controller = OnboardingController(settings, viewModelScope, requireWorkingDir = true)
}
