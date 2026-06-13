package com.github.reygnn.patcher.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.onboarding.OnboardingController

/**
 * Thin lifecycle wrapper around the shared [OnboardingController] (core-onboarding).
 * All onboarding logic is shared with Lobber/Caster; Patcher runs a system-wide
 * `apt` update and needs no working dir, so it is not required.
 */
class OnboardingViewModel(settings: SettingsStore) : ViewModel() {
    val controller = OnboardingController(settings, viewModelScope, requireWorkingDir = false)
}
