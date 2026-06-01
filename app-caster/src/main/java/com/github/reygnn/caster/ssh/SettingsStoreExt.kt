package com.github.reygnn.caster.ssh

import com.github.reygnn.core.data.SettingsStore
import kotlinx.coroutines.flow.first

suspend fun SettingsStore.resolveConfig(): SshConfig? {
    val servers = servers.first()
    if (servers.isEmpty()) return null
    val idx = selectedIndex.first().coerceIn(0, servers.lastIndex)
    val pem = readKeyPem() ?: return null
    return servers[idx].toSshConfig(pem)
}
