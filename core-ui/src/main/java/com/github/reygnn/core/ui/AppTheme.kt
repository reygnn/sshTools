package com.github.reygnn.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 wrapper with dynamic color (Material You). All three apps target
 * Android 16, so dynamicLight/DarkColorScheme are always available — no static
 * baseline fallback needed.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) dynamicDarkColorScheme(context)
                      else           dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colorScheme, content = content)
}
