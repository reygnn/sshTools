package com.github.reygnn.lobber.ssh

import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.data.resolveSelectedProfileAndKey

/**
 * Lobber-specific: resolves the currently selected profile + stored private key
 * into a [SshConfig], or null when unconfigured. The select/clamp/read-key logic
 * is shared in core-data ([resolveSelectedProfileAndKey]); only the app-shaped
 * [SshConfig] mapping is local (Hard Rule 1).
 */
suspend fun SettingsStore.resolveConfig(): SshConfig? =
    resolveSelectedProfileAndKey()?.let { (profile, pem) -> profile.toSshConfig(pem) }
