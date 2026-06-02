# ADDITIONAL_TESTS.md

Vorschläge zum Schließen der Integrations- und Instrumentierungslücken.

Stand: 2026-06-02. Kein TODO-Zwang — eine Ideensammlung, priorisiert nach
Wert/Aufwand. Der Großteil bleibt **JVM-only** und passt damit zur
„JUnit 4 + JVM-first"-Konvention; nur Tier 3 braucht wirklich ein Gerät.

Heutiger Stand: 196 Unit-Tests, alle grün. Die **Krypto-Primitive** sind
bereits gut abgedeckt (`TofuHostKeyVerifierTest` mintet echte Ed25519-Keys,
`SshSecurityTest` prüft den BC-Provider). Was fehlt, ist der **Draht-Pfad**,
die **Android-Runtime-Anbindung** (Keystore/DataStore) und die **UI**.

---

## Tier 1 — In-Process-SSH-Server (JVM, größter Hebel)

Die eigentliche Lücke ist der gesamte Draht-Pfad, der heute komplett gemockt
ist: `SshKeygen` → `BcOpenSshKeyProvider` → `connectWithKey` → echter
Handshake → `runCommand`-Drain → Exit-Status.

Schließbar **ohne Gerät** mit einem in-process gestarteten **Apache MINA
SSHD** (vom Client unabhängige Implementierung → fängt auch Interop-Bugs).
Bleibt in `src/test`, nur eine `testImplementation`-Dep
(`org.apache.sshd:sshd-core`, in den Version-Catalog).

```kotlin
// core-ssh/src/test/.../SshIntegrationTest.kt
class SshIntegrationTest {
    private val device = SshKeygen.generateEd25519()      // das echte App-Keypair
    private lateinit var sshd: SshServer
    private var port = 0

    @Before fun start() {
        SshSecurity.installBouncyCastle()
        sshd = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0                  // ephemerer Port
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            publickeyAuthenticator = PublickeyAuthenticator { _, key, _ ->
                key == BcOpenSshKeyProvider(device.privateKeyPem).public   // nur unser Key
            }
            commandFactory = ProcessShellCommandFactory.INSTANCE          // echtes /bin/sh
            start()
        }
        port = sshd.port
    }
    @After fun stop() = sshd.stop(true)

    @Test fun `generated Ed25519 key actually authenticates`() = runBlocking {
        connectWithKey("127.0.0.1", port, "u", device.privateKeyPem, null).use { ssh ->
            assertEquals(0, ssh.runCommand("true").exitStatus)
        }
    }

    @Test(timeout = 5_000) fun `runCommand drains over-cap output without deadlocking`() = runBlocking {
        connectWithKey("127.0.0.1", port, "u", device.privateKeyPem, null).use { ssh ->
            val r = ssh.runCommand("yes x | head -c 3000000", maxOutputBytes = 1 shl 20)
            assertEquals(1 shl 20, r.stdout.toByteArray().size)   // exakt gekappt, kein Hänger
        }
    }

    @Test fun `rotated host key aborts the second connect`() = runBlocking {
        val pin = AtomicReference<String?>()
        connectWithKey("127.0.0.1", port, "u", device.privateKeyPem, null,
            onLearnHostKey = { pin.set(it) }).use { }
        sshd.stop(true); start()                                  // neuer Host-Key
        assertThrows(Exception::class.java) {
            runBlocking { connectWithKey("127.0.0.1", port, "u", device.privateKeyPem, pin.get()).use { } }
        }
    }
}
```

Damit beweisbar — und heute ungetestet:

- **`BcOpenSshKeyProvider` end-to-end** (der ganze Conscrypt-Workaround-Sinn):
  generierter Key authentifiziert wirklich.
- **Drain-before-join (Hard Rule 3)** gegen einen echten Channel mit
  >1 MiB Output — der Deadlock, gegen den der Code designt ist.
- **TOFU über den Draht**: Lernen beim Erstconnect, harter Abbruch bei
  rotiertem Host-Key.
- **`streamCommand`-Teardown (AUDIT V5)**: langes `tail -f` starten, Collector
  canceln, mit `timeout=` prüfen, dass es prompt zurückkehrt statt zu hängen.
- **`SshjOnboarding` komplett**: `authorized_keys` auf eine Temp-Datei zeigen
  lassen, `pushPublicKey` → prüfen dass die Zeile landet → `verifyPubkeyAuth`
  gegen denselben Server grün.

> **Einschränkung:** Auf der Desktop-JVM liefert die JCE Ed25519 breit — der
> *spezifische* Conscrypt-Bug (PKCS#8-Preamble), den `BcOpenSshKeyProvider`
> umgeht, reproduziert hier **nicht**. Dieser Test beweist Protokoll-Verdrahtung
> und Logik; die Conscrypt-Regression fängt nur Tier 3.

---

## Tier 2 — Robolectric für `SettingsStore` (JVM, mittlerer Hebel)

`decodeKeyBlob` ist schon rein getestet (Decrypt injiziert). Ungetestet ist
der **DataStore-Round-Trip**:

- Legacy-Migration (flache `host`/`port`/`user`/`dir`-Keys → `KEY_SERVERS`).
- `learnHostFingerprint`-Idempotenz auf echtem Storage (nur unpinned matching
  Profile werden gepinnt).
- `restrictToOwner`-Permissions.
- `saveServers`/`setSelectedIndex`-Clamping der Selection.

```kotlin
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val store = SettingsStore(ApplicationProvider.getApplicationContext())
    @Test fun `legacy flat keys migrate to one profile on read`() = runTest { /* ... */ }
    @Test fun `learnHostFingerprint pins only unpinned matching profiles`() = runTest { /* ... */ }
}
```

**Voraussetzung für den Key-Pfad:** `saveKey`/`readKeyPem` rufen `object
KeyVault` hart auf — Robolectric hat keinen funktionierenden AndroidKeyStore.
Würde man die Cipher-Funktion injizieren (der `decrypt`-Seam existiert
read-seitig schon), wäre auch der Speicher-Pfad JVM-testbar; der echte
Keystore bliebe dann nur für Tier 3.

---

## Tier 3 — Instrumentiert (`androidTest`, das einzig gerätegebundene)

Nur **zwei** Dinge brauchen echtes Android-Runtime (TEE/Conscrypt). Wäre das
erste `androidTest`-SourceSet des Projekts — bewusst winzig, passend zur
Konvention „kein androidTest außer instrumentiertes Verhalten zählt wirklich".

1. **`KeyVault` Round-Trip gegen den echten Keystore** — `encrypt`/`decrypt`
   mit hardware-gebundenem Key, plus Tamper-Fall (Byte kippen →
   `AEADBadTagException`). Der einzige Test, der das „at rest"-Versprechen
   real verifiziert.
2. **`BcOpenSshKeyProvider` auf Conscrypt** — derselbe MINA-Test wie Tier 1,
   aber als `androidTest` auf Emulator/Gerät. *Hier* würde der
   Conscrypt-Preamble-Bug zuschlagen, falls die Workaround-Logik je bricht.

---

## Tier 4 — Compose-UI (erfordert kleinen Vorab-Refactor)

**Testbarkeits-Hürde:** Die Screens nehmen die VM direkt
(`fun OnboardingScreen(viewModel: OnboardingViewModel, …)`), nicht gehoisteten
State. Für günstige Compose-Tests den zustandslosen Kern extrahieren:

```kotlin
@Composable fun OnboardingScreen(viewModel: …) {        // dünner Wrapper
    OnboardingContent(state = c.state.collectAsState…, onConfirmHostKey = c::confirmHostKey, …)
}
@Composable fun OnboardingContent(state: OnboardingState, onConfirmHostKey: () -> Unit, …)  // testbar
```

Dann (sogar unter Robolectric mit `createComposeRule`, kein Gerät nötig):

```kotlin
@Test fun `host key dialog shows the fingerprint and confirm fires`() {
    var confirmed = false
    compose.setContent {
        OnboardingContent(OnboardingState(step = AwaitingHostKeyConfirm, pendingFingerprint = "SHA256:abc"),
                          onConfirmHostKey = { confirmed = true })
    }
    compose.onNodeWithText("SHA256:abc", substring = true).assertIsDisplayed()
    compose.onNodeWithText("Confirm").performClick()
    assertTrue(confirmed)
}
```

Lohnende Kandidaten:

- **Host-Key-Bestätigungsdialog** (Fingerprint sichtbar, Confirm/Cancel rufen
  das Richtige — die sicherheitskritische Nutzerentscheidung).
- **Self-Install-Dialog** in Lobber.
- **Log-Rendering / Auto-Scroll**.

---

## Priorisierung

Wenn nur eine Sache: **Tier 1 (MINA-SSHD)**. Höchster Wert pro Aufwand, bleibt
JVM-only, deckt das Herzstück ab, das aktuell ausschließlich gemockt ist —
inklusive der drei Stellen mit der meisten Sorgfalt im Code (Drain-Deadlock,
Cancel-Teardown, Host-Key-Pinning über den echten Handshake).
</content>
</invoke>
