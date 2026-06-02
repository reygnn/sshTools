package com.github.reygnn.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The configured servers plus the active profile index, for the server picker.
 * The picker stays hidden while [servers] has one or zero entries.
 */
data class ServerSelection(
    val servers: List<ServerProfile> = emptyList(),
    val selectedIndex: Int = 0,
)

/**
 * Eager [StateFlow] of the current [ServerSelection], with the index clamped
 * into range. Shared by all three apps' list ViewModels (Caster launcher,
 * Prodder sessions, Lobber installer) so the picker wiring can't drift; the
 * per-app reload after a profile switch stays in the ViewModel's `selectServer`.
 */
fun SettingsStore.serverSelectionState(scope: CoroutineScope): StateFlow<ServerSelection> =
    combine(servers, selectedIndex) { list, index ->
        ServerSelection(list, index.coerceIn(0, maxOf(0, list.lastIndex)))
    }.stateIn(scope, SharingStarted.Eagerly, ServerSelection())
