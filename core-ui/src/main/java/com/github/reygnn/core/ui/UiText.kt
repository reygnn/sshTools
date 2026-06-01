package com.github.reygnn.core.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * UI-Text, der entweder als String-Ressource oder als bereits aufgelöster
 * String getragen wird. ViewModels emittieren [UiText] statt direkter Strings,
 * sodass Composables die Ressource erst beim Rendern auflösen.
 */
sealed interface UiText {
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Literal(val value: String) : UiText
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Resource -> if (args.isEmpty()) context.getString(id)
                          else context.getString(id, *args.toTypedArray())
    is UiText.Literal  -> value
}

@Composable
fun UiText.resolve(): String = resolve(LocalContext.current)
