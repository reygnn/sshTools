package com.github.reygnn.core.data

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Robolectric coverage of [SettingsStore]'s Android-runtime glue: the real
 * `DataStore<Preferences>` round-trip, the `filesDir` key/pub-key files and the
 * one-time legacy migration. These need an Android `Context` (DataStore + files),
 * so they run under Robolectric rather than as plain JVM tests.
 *
 * The key *crypto* path (`saveKey`/`readKeyPem` via [KeyVault]) is deliberately
 * absent — it needs the hardware-backed Android Keystore, which Robolectric does
 * not provide; that belongs in an instrumented test (Tier 3). The pure blob
 * decision is already covered by [KeyBlobTest].
 *
 * Robolectric reuses `filesDir` (and the process-wide DataStore singleton) across
 * methods in a run, so [setUp] resets to a known-empty baseline to keep the tests
 * order-independent.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private lateinit var context: Context
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = SettingsStore(context)
        // Robolectric reuses filesDir/DataStore across methods; reset the baseline.
        runBlocking {
            store.saveServers(emptyList())
            store.setSelectedIndex(0)
            store.saveAdbHost("")
        }
        File(context.filesDir, "id_ed25519").delete()
        File(context.filesDir, "id_ed25519.pub").delete()
    }

    private fun profile(name: String, host: String = "host", port: Int = 22) =
        ServerProfile(name = name, host = host, port = port, username = "user", workingDir = "/dir")

    // ---- DataStore round-trip -------------------------------------------------

    @Test
    fun `servers round-trips through saveServers`() = runBlocking {
        assertEquals(emptyList<ServerProfile>(), store.servers.first()) // baseline from setUp
        val list = listOf(profile("A"), profile("B"))
        store.saveServers(list)
        assertEquals(list, store.servers.first())
    }

    @Test
    fun `setSelectedIndex clamps into the server range`() = runBlocking {
        store.saveServers(listOf(profile("A"), profile("B"), profile("C")))
        store.setSelectedIndex(10)
        assertEquals(2, store.selectedIndex.first())
        store.setSelectedIndex(-5)
        assertEquals(0, store.selectedIndex.first())
        store.setSelectedIndex(1)
        assertEquals(1, store.selectedIndex.first())
    }

    @Test
    fun `saveServers keeps the selection in range after a removal`() = runBlocking {
        store.saveServers(listOf(profile("A"), profile("B"), profile("C")))
        store.setSelectedIndex(2)
        store.saveServers(listOf(profile("A")))
        assertEquals(0, store.selectedIndex.first())
    }

    // ---- learnHostFingerprint (TOFU persistence, Hard Rule 5) -----------------

    @Test
    fun `learnHostFingerprint pins only the matching unpinned profile`() = runBlocking {
        store.saveServers(
            listOf(
                profile("match", host = "build", port = 22),
                profile("otherHost", host = "elsewhere", port = 22),
                profile("otherPort", host = "build", port = 2222),
            ),
        )
        store.learnHostFingerprint("build", 22, "SHA256:abc")

        val servers = store.servers.first()
        assertEquals("SHA256:abc", servers.first { it.name == "match" }.knownHostFingerprint)
        assertNull(servers.first { it.name == "otherHost" }.knownHostFingerprint)
        assertNull(servers.first { it.name == "otherPort" }.knownHostFingerprint)
    }

    @Test
    fun `learnHostFingerprint never overwrites an existing pin`() = runBlocking {
        store.saveServers(listOf(profile("p", host = "build", port = 22)))
        store.learnHostFingerprint("build", 22, "SHA256:first")
        store.learnHostFingerprint("build", 22, "SHA256:second")
        assertEquals("SHA256:first", store.servers.first().single().knownHostFingerprint)
    }

    // ---- isConfigured (servers AND key file present) --------------------------

    @Test
    fun `isConfigured is false with servers but no key file`() = runBlocking {
        store.saveServers(listOf(profile("A")))
        assertFalse(store.isConfigured.first())
    }

    @Test
    fun `isConfigured is true once both servers and a key file exist`() = runBlocking {
        File(context.filesDir, "id_ed25519").writeText("blob")
        store.saveServers(listOf(profile("A")))
        assertTrue(store.isConfigured.first())
    }

    // ---- adbHost (Lobber-only field) -----------------------------------------

    @Test
    fun `adbHost defaults to empty and round-trips`() = runBlocking {
        assertEquals(DEFAULT_ADB_HOST, store.adbHost.first())
        store.saveAdbHost("100.64.0.1")
        assertEquals("100.64.0.1", store.adbHost.first())
    }

    // ---- savePubKey writes the file ------------------------------------------

    @Test
    fun `savePubKey writes the public key into filesDir`() = runBlocking {
        store.savePubKey("ssh-ed25519 AAAA test@host")
        val pub = File(context.filesDir, "id_ed25519.pub")
        assertTrue(pub.exists())
        assertEquals("ssh-ed25519 AAAA test@host", pub.readText())
    }

    // ---- legacy migration via the internal readServers -----------------------

    @Test
    fun `readServers returns empty for empty preferences`() {
        assertEquals(emptyList<ServerProfile>(), store.readServers(preferencesOf()))
    }

    @Test
    fun `readServers migrates legacy flat keys into one profile`() {
        val prefs = preferencesOf(
            stringPreferencesKey("host") to "buildhost",
            stringPreferencesKey("user") to "ci",
            intPreferencesKey("port") to 2222,
            stringPreferencesKey("dir") to "/srv/builds",
        )
        assertEquals(
            listOf(ServerProfile("Server 1", "buildhost", 2222, "ci", "/srv/builds")),
            store.readServers(prefs),
        )
    }

    @Test
    fun `readServers without a legacy user yields empty`() {
        val prefs = preferencesOf(stringPreferencesKey("host") to "buildhost")
        assertEquals(emptyList<ServerProfile>(), store.readServers(prefs))
    }

    @Test
    fun `readServers parses a stored servers JSON list`() {
        val list = listOf(profile("A"), profile("B"))
        val prefs = preferencesOf(stringPreferencesKey("servers") to Json.encodeToString(list))
        assertEquals(list, store.readServers(prefs))
    }

    @Test
    fun `readServers treats corrupt servers JSON as empty`() {
        val prefs = preferencesOf(stringPreferencesKey("servers") to "{ not valid json")
        assertEquals(emptyList<ServerProfile>(), store.readServers(prefs))
    }

    @Test
    fun `a stored servers list takes precedence over legacy flat keys`() {
        val prefs = preferencesOf(
            stringPreferencesKey("servers") to Json.encodeToString(listOf(profile("new"))),
            stringPreferencesKey("host") to "legacyhost",
            stringPreferencesKey("user") to "legacyuser",
        )
        assertEquals(listOf(profile("new")), store.readServers(prefs))
    }
}
