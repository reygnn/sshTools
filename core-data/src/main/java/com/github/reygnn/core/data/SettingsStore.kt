package com.github.reygnn.core.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
            if (changed) {
                prefs[KEY_SERVERS] = json.encodeToString(updated)
                // TOFU is a deliberate trust decision: log the first-use pin so a
                // silent (re-)learn after a pin was lost is diagnosable (AUDIT R3
                // deferred note + V3/V8 context).
                Log.i(TAG, "Pinned host key for $host:$port (trust-on-first-use)")
            }
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

    /**
     * The shared private key PEM, or null if none is stored yet. Runs on
     * [Dispatchers.IO]: [readDecryptedKey] does a blocking file read plus an
     * Android-Keystore (TEE) decrypt, and callers invoke this from
     * `viewModelScope` (Main) once per SSH operation — most frequently Prodder's
     * ~2s session poll. Keeping it off the main thread avoids that recurring jank.
     */
    suspend fun readKeyPem(): String? = withContext(Dispatchers.IO) { readDecryptedKey() }

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
     * Decrypts the key from disk via [decodeKeyBlob] (legacy plaintext PEM,
     * absent, and decrypt-failure handling live there). A decrypt/authenticate
     * failure is logged so a tampered blob or wiped Keystore key is
     * distinguishable from "no key yet" (cf. R5). See AUDIT V8/V11.
     */
    private fun readDecryptedKey(): String? {
        if (!keyFile.exists()) return null
        return decodeKeyBlob(
            raw = keyFile.readText(),
            decrypt = { KeyVault.decrypt(it) },
            onError = { e -> Log.w(TAG, "Stored key blob failed to decrypt/authenticate", e) },
        )
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
