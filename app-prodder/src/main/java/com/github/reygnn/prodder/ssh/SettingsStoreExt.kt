package com.github.reygnn.prodder.ssh

import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.data.resolveSelectedProfileAndKey

/**
 * Resolves the currently selected profile + stored private key into a
 * [SshConfig], or null when unconfigured. Select/clamp/read-key logic is shared
 * in core-data ([resolveSelectedProfileAndKey]); only the [SshConfig] mapping is
 * app-local — Prodder's having no `workingDir` (Hard Rule 1).
 */
suspend fun SettingsStore.resolveConfig(): SshConfig? =
    resolveSelectedProfileAndKey()?.let { (profile, pem) -> profile.toSshConfig(pem) }
