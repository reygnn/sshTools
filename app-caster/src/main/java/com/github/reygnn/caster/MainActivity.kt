package com.github.reygnn.caster

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.reygnn.core.ui.AppTheme
import com.github.reygnn.caster.ui.LaunchViewModel
import com.github.reygnn.caster.ui.LauncherScreen
import com.github.reygnn.caster.ui.SettingsScreen
import com.github.reygnn.caster.ui.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val factory by lazy {
        val app = application as CasterApplication
        CasterViewModelFactory(app.settingsStore, app.applicationScope)
    }
    private val launchVm: LaunchViewModel by viewModels { factory }
    private val settingsVm: SettingsViewModel by viewModels { factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Verhindert Screenshots und blendet App-Switcher-Thumbnails aus —
        // schützt das Private-Key-Eingabefeld und potenziell sensiblen
        // Log-Output vor Schulterblick und Recents-Caching.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContent {
            AppTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "launcher") {
                    composable("launcher") {
                        // Beim Foreground frisch laden, beim Background Liste leeren
                        // (zeigt dann Spinner statt veralteter Einträge).
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
                        // done() persistiert und feuert savedEvents — dann zurück.
                        LaunchedEffect(Unit) {
                            settingsVm.savedEvents.collect { nav.popBackStack() }
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
