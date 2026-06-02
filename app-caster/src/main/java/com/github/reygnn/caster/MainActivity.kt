package com.github.reygnn.caster

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.reygnn.core.data.ConfigState
import com.github.reygnn.core.ui.AppTheme
import com.github.reygnn.caster.ui.LaunchViewModel
import com.github.reygnn.caster.ui.LauncherScreen
import com.github.reygnn.caster.ui.OnboardingScreen
import com.github.reygnn.caster.ui.OnboardingViewModel
import com.github.reygnn.caster.ui.SettingsScreen
import com.github.reygnn.caster.ui.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val factory by lazy {
        val app = application as CasterApplication
        CasterViewModelFactory(app.settingsStore, app.applicationScope)
    }
    private val launchVm: LaunchViewModel by viewModels { factory }
    private val settingsVm: SettingsViewModel by viewModels { factory }
    private val onboardingVm: OnboardingViewModel by viewModels { factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevents screenshots and hides app-switcher thumbnails —
        // protects the private-key input field and potentially sensitive
        // log output from shoulder-surfing and recents caching.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContent {
            AppTheme {
                val configState by settingsVm.configState.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "loading") {
                    composable("loading") {
                        LaunchedEffect(configState) {
                            when (configState) {
                                ConfigState.Configured   -> nav.navigate("launcher")    { popUpTo("loading") { inclusive = true } }
                                ConfigState.Unconfigured -> nav.navigate("onboarding")   { popUpTo("loading") { inclusive = true } }
                                ConfigState.Loading      -> Unit
                            }
                        }
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    composable("onboarding") {
                        OnboardingScreen(
                            viewModel = onboardingVm,
                            onDone = { nav.navigate("launcher") { popUpTo("onboarding") { inclusive = true } } },
                            onManual = { nav.navigate("settings") },
                        )
                    }
                    composable("launcher") {
                        // Reload fresh on foreground, clear the list on background
                        // (then shows a spinner instead of stale entries).
                        LifecycleResumeEffect(Unit) {
                            launchVm.loadProjects()
                            onPauseOrDispose { launchVm.clearProjects() }
                        }
                        LauncherScreen(
                            viewModel = launchVm,
                            versionName = BuildConfig.VERSION_NAME,
                            onOpenSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        // done() persists and fires savedEvents — then on to the launcher
                        // (works from both sources: launcher and onboarding "have key").
                        LaunchedEffect(Unit) {
                            settingsVm.savedEvents.collect {
                                nav.navigate("launcher") { popUpTo("settings") { inclusive = true } }
                            }
                        }
                        SettingsScreen(
                            viewModel = settingsVm,
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
