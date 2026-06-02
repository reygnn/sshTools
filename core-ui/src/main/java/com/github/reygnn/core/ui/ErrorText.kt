package com.github.reygnn.core.ui

import com.github.reygnn.core.ssh.RemoteCommandException

/**
 * Maps a caught [Throwable] to a [UiText] for display.
 *
 * A typed [RemoteCommandException] resolves to a localized resource (exit code
 * + stderr); any other throwable surfaces its own message (library-supplied,
 * e.g. sshj's "Connection refused"), falling back to a generic resource when
 * there is none.
 *
 * Centralizes the error→UiText mapping that previously lived inline in every
 * ViewModel — so the localization path can't drift between apps, and the SSH
 * layer no longer needs hardcoded user-facing strings.
 */
fun Throwable.toUiText(): UiText = when (this) {
    is RemoteCommandException ->
        UiText.Resource(R.string.cu_error_remote_command, listOf(exitStatus, stderr.ifBlank { "—" }))
    else -> message?.takeIf { it.isNotBlank() }?.let(UiText::Literal)
        ?: UiText.Resource(R.string.cu_error_unknown)
}
