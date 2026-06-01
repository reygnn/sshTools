package com.github.reygnn.lobber.ssh

import com.github.reygnn.core.data.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Lobber-specific: resolves the currently selected [ServerProfile] + stored
 * private key into a [SshConfig]. Returns null when unconfigured.
 *
 * Replaces the `settings.config` Flow from the single-app version — suspend
 * functions compose more naturally across modules than a Flow that needs to
 * read from disk synchronously inside map{}.
 */
suspend fun SettingsStore.resolveConfig(): SshConfig? {
    val servers = servers.first()
    if (servers.isEmpty()) return null
    val idx = selectedIndex.first().coerceIn(0, servers.lastIndex)
    val profile = servers[idx]
    val pem = readKeyPem() ?: return null
    return profile.toSshConfig(pem)
}
