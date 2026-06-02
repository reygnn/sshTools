package com.github.reygnn.prodder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.onboarding.OnboardingController

/**
 * Thin lifecycle wrapper around the shared [OnboardingController] (core-onboarding).
 * All onboarding logic is shared with Lobber/Caster; Prodder only attaches to
 * existing sessions and needs no working dir, so it is not required.
 */
class OnboardingViewModel(settings: SettingsStore) : ViewModel() {
    val controller = OnboardingController(settings, viewModelScope, requireWorkingDir = false)
}
