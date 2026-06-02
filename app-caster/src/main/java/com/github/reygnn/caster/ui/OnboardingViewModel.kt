package com.github.reygnn.caster.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.onboarding.OnboardingController

/**
 * Thin lifecycle wrapper around the shared [OnboardingController] (core-onboarding).
 * All onboarding logic is shared with Lobber/Prodder; Caster runs `screen`
 * sessions in a build dir, so a working dir is required.
 */
class OnboardingViewModel(settings: SettingsStore) : ViewModel() {
    val controller = OnboardingController(settings, viewModelScope, requireWorkingDir = true)
}
