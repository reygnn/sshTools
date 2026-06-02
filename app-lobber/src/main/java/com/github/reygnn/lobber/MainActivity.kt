package com.github.reygnn.lobber

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.reygnn.core.data.ConfigState
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ui.AppTheme
import com.github.reygnn.lobber.ui.InstallViewModel
import com.github.reygnn.lobber.ui.InstallerScreen
import com.github.reygnn.lobber.ui.OnboardingScreen
import com.github.reygnn.lobber.ui.OnboardingViewModel
import com.github.reygnn.lobber.ui.SettingsScreen
import com.github.reygnn.lobber.ui.SettingsViewModel
import kotlinx.coroutines.CoroutineScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Verhindert Screenshots und blendet App-Switcher-Thumbnails aus —
        // schützt das Private-Key-Eingabefeld und potenziell sensiblen
        // Log-Output vor Schulterblick und Recents-Caching.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val app = application as LobberApplication
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LobberApp(app.settingsStore, app.applicationScope)
                }
            }
        }
    }
}

@Composable
private fun LobberApp(store: SettingsStore, appScope: CoroutineScope) {
    val factory = remember(store, appScope) { LobberViewModelFactory(store, appScope) }
    val settingsVm: SettingsViewModel = viewModel(factory = factory)
    val installVm: InstallViewModel   = viewModel(factory = factory)
    val onboardingVm: OnboardingViewModel = viewModel(factory = factory)

    val configState by settingsVm.configState.collectAsStateWithLifecycle()
    val nav = rememberNavController()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, installVm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) installVm.clearAabs()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(navController = nav, startDestination = "loading") {
        composable("loading") {
            LaunchedEffect(configState) {
                when (configState) {
                    ConfigState.Configured   -> nav.navigate("installer")  { popUpTo("loading") { inclusive = true } }
                    ConfigState.Unconfigured -> nav.navigate("onboarding") { popUpTo("loading") { inclusive = true } }
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
                onDone = { nav.navigate("installer") { popUpTo("onboarding") { inclusive = true } } },
                onManual = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = settingsVm,
                onSaved = { nav.navigate("installer") { popUpTo("settings") { inclusive = true } } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("installer") {
            InstallerScreen(viewModel = installVm, onOpenSettings = { nav.navigate("settings") })
        }
    }
}
