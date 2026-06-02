package com.github.reygnn.core.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * UI text carried either as a string resource or as an already-resolved
 * string. ViewModels emit [UiText] instead of direct strings,
 * so that Composables resolve the resource only at render time.
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
