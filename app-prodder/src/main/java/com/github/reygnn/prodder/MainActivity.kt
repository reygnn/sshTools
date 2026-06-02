package com.github.reygnn.prodder

import android.net.Uri
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.reygnn.core.data.ConfigState
import com.github.reygnn.core.ui.AppTheme
import com.github.reygnn.prodder.ui.OnboardingScreen
import com.github.reygnn.prodder.ui.OnboardingViewModel
import com.github.reygnn.prodder.ui.SessionScreen
import com.github.reygnn.prodder.ui.SessionViewModel
import com.github.reygnn.prodder.ui.SessionsScreen
import com.github.reygnn.prodder.ui.SessionsViewModel
import com.github.reygnn.prodder.ui.SettingsScreen
import com.github.reygnn.prodder.ui.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val factory by lazy {
        val app = application as ProdderApplication
        ProdderViewModelFactory(app.settingsStore, app.applicationScope)
    }
    private val sessionsVm: SessionsViewModel by viewModels { factory }
    private val sessionVm: SessionViewModel by viewModels { factory }
    private val settingsVm: SettingsViewModel by viewModels { factory }
    private val onboardingVm: OnboardingViewModel by viewModels { factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Verhindert Screenshots und blendet App-Switcher-Thumbnails aus —
        // schützt das Private-Key-Eingabefeld und potenziell sensiblen
        // Terminal-Output vor Schulterblick und Recents-Caching.
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
                                ConfigState.Configured   -> nav.navigate("sessions")   { popUpTo("loading") { inclusive = true } }
                                ConfigState.Unconfigured -> nav.navigate("onboarding")  { popUpTo("loading") { inclusive = true } }
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
                            onDone = { nav.navigate("sessions") { popUpTo("onboarding") { inclusive = true } } },
                            onManual = { nav.navigate("settings") },
                        )
                    }
                    composable("sessions") {
                        // Beim Foreground frisch laden, beim Background Liste leeren
                        // (zeigt dann Spinner statt veralteter Einträge).
                        LifecycleResumeEffect(Unit) {
                            sessionsVm.loadSessions()
                            onPauseOrDispose { sessionsVm.clearSessions() }
                        }
                        SessionsScreen(
                            viewModel = sessionsVm,
                            versionName = BuildConfig.VERSION_NAME,
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenSession = { id, name ->
                                nav.navigate("session/${Uri.encode(id)}/${Uri.encode(name)}")
                            },
                        )
                    }
                    composable(
                        route = "session/{id}/{name}",
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType },
                        ),
                    ) { backStackEntry ->
                        val id = backStackEntry.arguments?.getString("id").orEmpty()
                        val name = backStackEntry.arguments?.getString("name").orEmpty()
                        LaunchedEffect(id) { sessionVm.bind(id, name) }
                        SessionScreen(
                            viewModel = sessionVm,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("settings") {
                        // done() persistiert und feuert savedEvents — danach zur Sessions-Liste
                        // (funktioniert aus beiden Quellen: Sessions und Onboarding-"hab Key").
                        LaunchedEffect(Unit) {
                            settingsVm.savedEvents.collect {
                                nav.navigate("sessions") { popUpTo("settings") { inclusive = true } }
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
