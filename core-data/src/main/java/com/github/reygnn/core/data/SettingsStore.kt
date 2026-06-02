package com.github.reygnn.core.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

private val Context.dataStore by preferencesDataStore(name = "ssh-tools-settings")

/**
 * Gemeinsamer Default für das ADB-Reconnect-Feld (Lobber). Bewusst leer —
 * der Nutzer trägt die Phone-Adresse selbst ein. Caster und Prodder lesen
 * dieses Feld nicht; es ist harmlos in deren DataStore vorhanden.
 */
const val DEFAULT_ADB_HOST = ""

/**
 * **Geteilter Code**, nicht geteilte Daten: dieselbe Klasse wird von allen drei
 * Apps (Lobber, Caster, Prodder) verwendet, aber jede App hat ihre eigene
 * Sandbox. DataStore-Name und Keystore-Alias sind zwar überall identisch,
 * liegen aber je App getrennt — drei eigene DataStore-Dateien, drei eigene
 * `id_ed25519`, drei UID-gebundene Keystore-Schlüssel. Jede App konfiguriert
 * und onboardet daher unabhängig (siehe [KeyVault]).
 *
 * SSH-Konfiguration in `DataStore<Preferences>`, Private-Key als Datei in
 * `filesDir`. Der Key liegt **verschlüsselt at rest** ([KeyVault], AES-256-GCM
 * mit nicht-exportierbarem Android-Keystore-Schlüssel) und owner-only auf der
 * Platte.
 *
 * Mehrere [ServerProfile]s werden als JSON-Liste unter [KEY_SERVERS] abgelegt,
 * das aktuell gewählte Profil über [KEY_SELECTED] (Index). Der Private-Key wird
 * innerhalb *einer* App von allen Profilen gemeinsam genutzt (eine Datei,
 * ein Keystore-Alias pro App).
 *
 * App-spezifische Nutzung:
 * - **Lobber/Caster**: [ServerProfile.workingDir] wird befüllt.
 * - **Prodder**: [ServerProfile.workingDir] bleibt `""` (Default).
 * - **Lobber**: nutzt zusätzlich [adbHost] / [saveAdbHost].
 * - **Caster/Prodder**: ignorieren [adbHost].
 *
 * `toSshConfig()` ist bewusst nicht hier — `SshConfig` ist pro App
 * unterschiedlich (Prodder hat kein `workingDir`). Jede App konvertiert
 * [ServerProfile] selbst in ihre lokale `SshConfig`.
 */
class SettingsStore(private val context: Context) {

    private val keyFile = File(context.filesDir, "id_ed25519")
    private val pubKeyFile = File(context.filesDir, "id_ed25519.pub")

    val servers: Flow<List<ServerProfile>> = context.dataStore.data.map { prefs ->
        readServers(prefs)
    }

    val selectedIndex: Flow<Int> = context.dataStore.data.map { prefs ->
        val servers = readServers(prefs)
        (prefs[KEY_SELECTED] ?: 0).coerceIn(0, maxOf(0, servers.lastIndex))
    }

    /** Emits true once at least one server *and* the key file are on disk. */
    val isConfigured: Flow<Boolean> = context.dataStore.data.map { prefs ->
        readServers(prefs).isNotEmpty() && keyFile.exists()
    }

    /**
     * Pins [fingerprint] (OpenSSH `SHA256:…`) onto every profile that matches
     * [host]/[port] and isn't pinned yet — the trust-on-first-use persistence
     * step. Idempotent: a profile that already has a fingerprint is left
     * untouched (a *mismatch* is rejected at connect time, never silently
     * overwritten here).
     */
    suspend fun learnHostFingerprint(host: String, port: Int, fingerprint: String) {
        context.dataStore.edit { prefs ->
            val servers = readServers(prefs)
            var changed = false
            val updated = servers.map { p ->
                if (p.host == host && p.port == port && p.knownHostFingerprint == null) {
                    changed = true
                    p.copy(knownHostFingerprint = fingerprint)
                } else {
                    p
                }
            }
            if (changed) prefs[KEY_SERVERS] = json.encodeToString(updated)
        }
    }

    suspend fun saveServers(servers: List<ServerProfile>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVERS] = json.encodeToString(servers)
            // Keep the selection in range after add/remove.
            val sel = prefs[KEY_SELECTED] ?: 0
            prefs[KEY_SELECTED] = sel.coerceIn(0, maxOf(0, servers.lastIndex))
        }
    }

    suspend fun setSelectedIndex(index: Int) {
        context.dataStore.edit { prefs ->
            val servers = readServers(prefs)
            prefs[KEY_SELECTED] = index.coerceIn(0, maxOf(0, servers.lastIndex))
        }
    }

    /**
     * Writes the shared private key, **encrypted at rest** ([KeyVault]) and
     * owner-only as defence-in-depth.
     *
     * Permissions are tightened on an empty file *before* the (encrypted) bytes
     * are written, so nothing touches disk under default permissions. What lands
     * on disk is Base64 of the AES-256-GCM ciphertext, never the plaintext PEM.
     */
    suspend fun saveKey(privateKeyPem: String) {
        writeOwnerOnly(keyFile, Base64.getEncoder().encodeToString(KeyVault.encrypt(privateKeyPem)))
    }

    /** The shared private key PEM, or null if none is stored yet. */
    suspend fun readKeyPem(): String? = readDecryptedKey()

    /**
     * Schreibt die `ssh-ed25519 …`-Zeile als `id_ed25519.pub` in `filesDir`.
     */
    suspend fun savePubKey(publicKeyOpenSsh: String) {
        writeOwnerOnly(pubKeyFile, publicKeyOpenSsh)
    }

    /**
     * Tailscale-IP des Phones für den ADB-Reconnect (nur Lobber).
     * Caster und Prodder lesen dieses Feld nicht; es ist harmlos persistiert.
     */
    val adbHost: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ADB_HOST] ?: DEFAULT_ADB_HOST
    }

    suspend fun saveAdbHost(host: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ADB_HOST] = host }
    }

    /**
     * Decrypts the key from disk. A legacy plaintext PEM (installs from before
     * at-rest encryption) is recognised by the `-----` prefix and returned
     * as-is — Base64 ciphertext never starts with `-`, so the case is
     * unambiguous; the next [saveKey] rewrites it encrypted. Returns `null` if
     * absent or if decryption fails (e.g. the Keystore key was wiped — the blob
     * is then unrecoverable and re-onboarding is required).
     */
    private fun readDecryptedKey(): String? {
        if (!keyFile.exists()) return null
        val raw = keyFile.readText().trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("-----")) return raw
        return runCatching { KeyVault.decrypt(Base64.getDecoder().decode(raw)) }
            .getOrElse { e ->
                // A GCM auth-tag failure here means the key blob was tampered with;
                // other failures mean the Keystore key was wiped. Both currently
                // degrade to "not configured" — at least log it so the failure is
                // distinguishable from "no key yet" (cf. R5). See AUDIT V8.
                Log.w(TAG, "Stored key blob failed to decrypt/authenticate", e)
                null
            }
    }

    /**
     * Schreibt [content] owner-only. Die Rechte werden auf der **leeren** Datei
     * gesetzt, *bevor* der Inhalt geschrieben wird — so existieren die Secret-
     * Bytes nie mit Default-Rechten auf der Platte.
     */
    private fun writeOwnerOnly(file: File, content: String) {
        if (!file.exists()) file.createNewFile()
        if (!restrictToOwner(file)) {
            Log.w(TAG, "Konnte Rechte auf ${file.name} nicht vollständig einschränken")
        }
        file.writeText(content)
    }

    private fun restrictToOwner(file: File): Boolean =
        file.setReadable(false, false) and
            file.setReadable(true, true) and
            file.setWritable(false, false) and
            file.setWritable(true, true)

    /**
     * Liest die Profil-Liste. Fehlt [KEY_SERVERS] noch (frische Installation
     * oder Altdaten aus der Einzel-Server-Ära), wird einmalig aus den
     * Legacy-Flachschlüsseln (`host`/`port`/`user`/`dir`) ein Profil
     * synthetisiert — read-time, ohne Rückschreiben, sodass keine Migration
     * verloren geht.
     */
    private fun readServers(prefs: Preferences): List<ServerProfile> {
        prefs[KEY_SERVERS]?.let { stored ->
            return runCatching { json.decodeFromString<List<ServerProfile>>(stored) }
                .getOrElse { e ->
                    // A single corrupt entry drops the whole list to empty (app
                    // then looks "unconfigured"). Log it so the decode failure is
                    // at least diagnosable instead of silently swallowed.
                    Log.w(TAG, "Failed to decode stored server list; treating as empty", e)
                    emptyList()
                }
        }
        // Legacy-Migration: Lobber/Caster hatten host/port/user/dir als Flachschlüssel.
        val host = prefs[KEY_HOST] ?: return emptyList()
        val user = prefs[KEY_USER] ?: return emptyList()
        return listOf(
            ServerProfile(
                name = "Server 1",
                host = host,
                port = prefs[KEY_PORT] ?: 22,
                username = user,
                workingDir = prefs[KEY_DIR] ?: "",
            )
        )
    }

    private companion object {
        const val TAG = "SettingsStore"
        val json = Json { ignoreUnknownKeys = true }

        // Legacy flat keys — only read for one-time migration into KEY_SERVERS.
        val KEY_HOST: Preferences.Key<String> = stringPreferencesKey("host")
        val KEY_PORT: Preferences.Key<Int> = intPreferencesKey("port")
        val KEY_USER: Preferences.Key<String> = stringPreferencesKey("user")
        val KEY_DIR: Preferences.Key<String> = stringPreferencesKey("dir")

        val KEY_ADB_HOST: Preferences.Key<String> = stringPreferencesKey("adb_host")
        val KEY_SERVERS: Preferences.Key<String> = stringPreferencesKey("servers")
        val KEY_SELECTED: Preferences.Key<Int> = intPreferencesKey("selected")
    }
}
