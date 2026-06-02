# MEMORY — Projekt-Handoff sshTools

Kurznotiz für den Wiedereinstieg (auch nach einem Kontext-Reset). Ergänzt — nicht
ersetzt — `CLAUDE.md` (Konventionen), `audit/AUDIT.md` (Befunde) und `TODO.md`
(Fortschritt). Stand: **2026-06-02**.
Versionen: Lobber 0.6.1 (29), Caster 0.5.1 (9), Prodder 0.2.1 (6).

---

## Aktueller Stand

Eine vollständige **core-Konsolidierung** ist abgeschlossen: alle Findings aus
`audit/AUDIT.md` (A1–A8, B1–B4) sind umgesetzt und in `TODO.md` abgehakt.
`./gradlew test`, `assembleDebug` und `clean lint` (0 Issues über alle 7 Module)
sind grün. `main` == `origin/main`, alles gepusht, keine offenen Branches.

Ziel der Arbeit war: **Gemeinsamkeiten der drei Apps nach `core-*` ziehen, um
Drift zu vermeiden** — ohne Hard Rule 1 zu verletzen.

## Wo geteilter Code jetzt lebt (Ergebnis der Konsolidierung)

- `core-ui/SettingsComponents.kt` — stateless Composables `ServerRow`,
  `ServerEditor` (workingDir optional via nullable Label), `ServerPicker`,
  `StatusDot`, `KeyField` (maskiert + Show/Hide-Toggle). Reine Daten + Callbacks,
  **keine** VM-Kopplung. App-Screens bleiben Kompositions-Wurzel.
- `core-ui/LogView.kt` — `LogLineRow(line)` (geteiltes stdout/stderr/exit-
  Rendering). Dafür hängt `core-ui` an `core-ssh` (nur für `LogLine`).
- `core-ui` Strings: geteilte UI-Strings als `cu_*` in `values/` + `values-de/`
  (einmal gepflegt). App-spezifische Strings liegen pro App, jeweils EN + DE.
- `core-ssh/SshSession.kt` — `connectWithKey(...)` (Verifier + Timeout + connect +
  authPublickey) und `SSHClient.runCommand(...): CommandResult` (stdout+stderr
  nebenläufig drainen, **await vor join** = Hard Rule 3). `core-ssh` hängt jetzt
  an `kotlinx-coroutines-core`.
- `core-ssh/ScreenSessions.kt` — `parseScreenSessions(...)` + `ScreenSessionInfo`
  (geteiltes `screen -ls`-Parsing; Caster/Prodder mappen darauf).
- `core-data/ServerProfile.kt` — `ServerProfile?.pinToKeepFor(host, port)`
  (Host-Key-Pin beim Editieren behalten/zurücksetzen).
- `core-ssh/src/test/...` — Tests für die core-Primitives (vorher unter
  app-lobber); `TofuHostKeyVerifierTest` einmal statt 3×.

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
  Der grüne Build war dort nur ein Compile-/Typcheck. **Vor dem nächsten Release
  manueller Rauchtest gegen einen Build-Host** (onboard, list, launch/capture,
  install) empfohlen.
- Gotcha: nach einem Resource-**Verzeichnis-Rename** kann der inkrementelle
  Build stale werden (`mipmap/... not found`) → `./gradlew clean` behebt es.

## Pointer

- Konventionen + Hard Rules: `CLAUDE.md`
- Audit-Befunde (inkl. §C „bewusst nicht konsolidieren"): `audit/AUDIT.md`
- Aufgaben-Tracking (alles abgehakt): `TODO.md`
