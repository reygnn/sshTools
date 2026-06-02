# MEMORY — Projekt-Handoff sshTools

Kurznotiz für den Wiedereinstieg (auch nach einem Kontext-Reset). Ergänzt — nicht
ersetzt — `CLAUDE.md` (Konventionen) und `audit/AUDIT.md` (Befunde +
Umsetzungs-Chronik). Stand: **2026-06-02**.
Versionen: Lobber 0.6.1 (29), Caster 0.5.1 (9), Prodder 0.2.1 (6).

---

## Aktueller Stand

Mehrere **core-Konsolidierungen + Audit-Runden** sind abgeschlossen:

1. **Erste Runde** (alte Audit-Taxonomie A1–A8/B1–B4): geteilte UI-/SSH-/
   Parsing-Primitive nach `core-*`.
2. **Zweites Audit** (`audit/AUDIT.md`, Funde #1–#12): typisierte/lokalisierte
   SSH-Fehler, Streaming-Log-Cap, Lobber-Lifecycle angeglichen, Server-Form-/
   Selection-Logik nach `core-data`.
3. **Runde 3** (R1–R8): Prodder-Auto-Refresh lifecycle-bewusst, In-Flight-Guards,
   L10n-Lecks, `parseScreenSessions`-Edge-Case, `readServers`-Logging, Build-Hygiene.
4. **Runde 4 (vertieft, V1–V11):** Korrektheit/Concurrency/Security statt nur Drift —
   u. a. Streaming-Exit-Code als Erfolg/Fehler in der UI (V1), `LC_ALL=C` (V2),
   Host-Key-Pin-Race im Settings-CRUD (V3), **zweiphasiges Onboarding: Passwort erst
   nach Host-Key-Bestätigung (V4)**, fail-safe statt fail-open (V9), Cancellation-
   Teardown im Streaming (V5).
5. **Feature `core-onboarding`:** der V4-Flow generalisiert in ein eigenes Modul
   (`OnboardingController`) + core-ssh-Primitive (`SshOnboarding`); **alle drei Apps**
   onboarden jetzt gleich (eigener Key pro App, Hard Rule 4). Am Gerät (Emulator)
   verifiziert: Onboarding/Host-Key-Dialog, Liste, Launch (exit 0 + exit 1), Capture, Send.

`./gradlew testDebugUnitTest` + `lintDebug` (0 Issues über alle 8 Module) +
`bundleRelease` sind grün. `main` == `origin/main`, alles gepusht, keine offenen Branches.

Ziel durchgängig: **Gemeinsamkeiten der drei Apps nach `core-*` ziehen, um Drift
zu vermeiden** — ohne Hard Rule 1 zu verletzen.

## Wo geteilter Code jetzt lebt (Ergebnis der Konsolidierung)

- `core-ui/SettingsComponents.kt` — stateless Composables `ServerRow`,
  `ServerEditor` (workingDir optional via nullable Label), `ServerPicker`,
  `StatusDot`, `KeyField` (maskiert + Show/Hide-Toggle). Reine Daten + Callbacks,
  **keine** VM-Kopplung. App-Screens bleiben Kompositions-Wurzel.
- `core-ui/LogView.kt` — `LogLineRow(line)` (geteiltes stdout/stderr/exit-
  Rendering). Dafür hängt `core-ui` an `core-ssh` (nur für `LogLine`).
- `core-ui/ErrorText.kt` — `Throwable.toUiText()`: zentrales Fehler→`UiText`-
  Mapping (vorher 11× inline in den VMs). `RemoteCommandException` → lokalisierte
  Resource `cu_error_remote_command`, sonst die Message (Fallback `cu_error_unknown`).
- `core-ui` Strings: geteilte UI-Strings als `cu_*` in `values/` + `values-de/`
  (einmal gepflegt). App-spezifische Strings liegen pro App, jeweils EN + DE.
- `core-ssh/SshSession.kt` — `connectWithKey(...)` (Verifier + Timeout + connect +
  authPublickey), `SSHClient.runCommand(...): CommandResult` (stdout+stderr
  nebenläufig drainen, **await vor join** = Hard Rule 3) und `RemoteCommandException`
  (typisierter Nicht-0-Exit; UI-seitig via `toUiText()` lokalisiert). `core-ssh`
  hängt an `kotlinx-coroutines-core`.
- `core-ssh/LogLine.kt` — `List<LogLine>.plusCapped()` + `DEFAULT_MAX_LOG_LINES`
  (5000): kappt das im VM-State akkumulierte Streaming-Log, an allen 3 Append-Stellen.
- `core-ssh/ScreenSessions.kt` — `parseScreenSessions(...)` + `ScreenSessionInfo`
  (geteiltes `screen -ls`-Parsing; Caster mappt auf `ProjectEntry`, Prodder auf
  sein app-lokales `ScreenSession` — bewusst, siehe AUDIT #10).
- `core-data/ServerProfile.kt` — `ServerProfile?.pinToKeepFor(host, port)`
  (Host-Key-Pin beim Editieren behalten/zurücksetzen).
- `core-data/ServerEditing.kt` — `ServerForm`, `ServerForm.validate(existing,
  requireWorkingDir): ServerFormResult`, `ServerProfile.toForm(index)`,
  `List<ServerProfile>.upsert(...)`: Server-Editor-Validierung + Pin-Erhalt einmal
  getestet; die 3 `SettingsViewModel`s delegieren (Prodder mit `requireWorkingDir=false`).
- `core-data/ServerSelection.kt` — `ServerSelection` +
  `SettingsStore.serverSelectionState(scope)` (Picker-Flow). `core-data` hängt
  dafür jetzt **ebenfalls** an `kotlinx-coroutines-core`.
- `core-ssh/SshOnboarding.kt` — `SshOnboarding`/`SshjOnboarding`: app-agnostische
  Onboarding-Primitive (`discoverHostKey`/`pushPublicKey`/`verifyPubkeyAuth`, nur
  host/port/credentials, kein `SshConfig` → Hard Rule 1 unberührt).
- `core-onboarding/` (5. Core-Modul, hängt an core-ssh + core-data) —
  `OnboardingController`: zweiphasige Onboarding-State-Machine (V4) + typisierter
  `OnboardingError` (Apps mappen auf `UiText`). Lifecycle-frei (nimmt `CoroutineScope`).
  Die 3 Apps wrappen ihn in einen dünnen `OnboardingViewModel` + eigenen
  `OnboardingScreen`; `requireWorkingDir` ist der einzige App-Unterschied (Prodder=false).
  Tests einmal als `OnboardingControllerTest` (statt 3× pro App).
- `core-ssh/src/test/...` — Tests für die core-Primitives (vorher unter
  app-lobber); `TofuHostKeyVerifierTest` einmal statt 3×. Neu: `LogLineTest`
  (`plusCapped`) und `core-data/ServerFormTest` (`validate`/`toForm`/`upsert`).

## Invarianten / Entscheidungen, die zu respektieren sind

- **Hard Rule 1:** `SshConfig`, `SshClient`, `SshjClient`, `resolveConfig()`
  bleiben **pro App** (fachlich verschieden). Das core-Gerüst nimmt nur
  Primitiven, nie diese Typen. Nicht „zum Teilen" nach core ziehen.
- **Host-Key-Persistenz** ist in allen drei Apps identisch verdrahtet: in der
  `…ViewModelFactory` über `applicationScope` (überlebt VM-Clear), nicht im VM.
  Caster/Prodder haben dafür einen `applicationScope` wie Lobber.
- **Learn-Callback** überall `onLearnHostKey: (String) -> Unit` (non-suspend,
  direkt am Verifier).
- **SDK 36 ist bewusste Baseline** (CLAUDE.md): `min = target = compile = 36`,
  **nicht** auf 37 hochziehen. Deshalb unterdrückt `lint.xml` (Root, von allen
  Modulen automatisch geerbt) `GradleDependency` + `OldTargetApi`.
- **`TrustAllX509TrustManager`** kommt aus BouncyCastle `bcpkix` (TLS) und ist
  irrelevant (Apps machen nur SSH, kein TLS) → in `lint.xml` unterdrückt.
- DE-Lokalisierung: System-sprachgesteuert (EN default + `values-de` Overlay);
  In-App-Sprachwahl ist **bewusst nicht** gewünscht.

## Offene Folge-Themen / Caveats

- **Keine automatisierten Tests für die SSH-Plumbing-Schicht** (`connectWithKey`,
  `runCommand`, die echten connect/stream-Pfade) — braucht einen echten Host.
  Der grüne Build ist dort nur ein Compile-/Typcheck. **Geräte-Rauchtest am
  2026-06-02** (Emulator gegen lokalen sshd) deckte onboard/host-key-dialog, list,
  launch (exit 0 **und** exit 1 via Lobber), capture und send live ab — vor einem
  Release ähnlich gegen den echten Build-Host wiederholen.
- Gotcha: nach einem Resource-**Verzeichnis-Rename** kann der inkrementelle
  Build stale werden (`mipmap/... not found`) → `./gradlew clean` behebt es.

## Pointer

- Konventionen + Hard Rules: `CLAUDE.md`
- Audit-Funde + Umsetzungs-Chronik (Status je Fund, Abschnitt E „bewusst nicht
  anfassen"): `audit/AUDIT.md`
