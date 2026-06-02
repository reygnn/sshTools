package com.github.reygnn.core.data

import kotlinx.serialization.Serializable

/**
 * One named build-host profile. Multiple profiles let the user reach the
 * *same* build host over different paths (e.g. a LAN address and a Tailscale
 * address) — or genuinely different hosts — and switch between them.
 *
 * The SSH keypair is **shared** across all profiles (one `id_ed25519` in
 * filesDir): the same public key is authorized on each host.
 *
 * [knownHostFingerprint] is the pinned host-key fingerprint (OpenSSH
 * `SHA256:…`), learned trust-on-first-use and persisted per profile. `null`
 * means "not yet pinned" — the next connect learns and stores it. It is
 * deliberately per-profile (not global) so distinct hosts don't collide.
 *
 * [workingDir] ist für Lobber und Caster erforderlich (Pfad zum Build-
 * Verzeichnis auf dem Host). Prodder braucht ihn nicht — der Default `""`
 * signalisiert „nicht gesetzt"; Prodder-Profile setzen ihn nie.
 * `ignoreUnknownKeys = true` im Json-Decoder stellt sicher, dass ältere
 * JSON-Blobs ohne das Feld problemlos gelesen werden.
 */
@Serializable
data class ServerProfile(
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val workingDir: String = "",
    val knownHostFingerprint: String? = null,
)

/**
 * The host-key fingerprint to carry over when an existing profile is edited into
 * a new [host]/[port]: the pin is kept only while the endpoint (host + port) is
 * unchanged, so a moved endpoint re-establishes trust on the next connect
 * (renaming or changing the username keeps the pin). Returns null for a
 * brand-new profile (null receiver). Shared by all three apps' `saveServer()`.
 */
fun ServerProfile?.pinToKeepFor(host: String, port: Int): String? =
    this?.takeIf { it.host == host && it.port == port }?.knownHostFingerprint
