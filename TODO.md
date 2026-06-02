# TODO — Konsolidierung nach `core-*`

Abgeleitet aus [`AUDIT.md`](./audit/AUDIT.md) (2026-06-02). **Ziel:** Gemeinsamkeiten
der drei Apps nach `core-*` verlagern, damit Drift strukturell nicht mehr
entstehen kann. Reihenfolge: erst risikolose Cruft-Entfernung, dann
Vereinheitlichung, dann die größeren Hoists.

Hard Rule 1 bleibt: `SshConfig` / `SshClient` / `SshjClient` / `resolveConfig()`
werden **nicht** nach core gehoben (siehe AUDIT §C).

Legende: 🟢 risikolos · 🟡 mittel · 🔴 größer (eigener Branch).

---

## Phase 1 — Cruft & Quick Wins (risikolos)

- [x] **B3 · Totes Serialization-Plugin entfernen** 🟢 (erledigt 2026-06-02)
  `alias(libs.plugins.kotlin.serialization)` aus `app-lobber`, `app-caster`,
  `app-prodder` `build.gradle.kts` entfernen; dazu Lobbers
  `implementation(libs.kotlinx.serialization.json)`.
  *Akzeptanz:* `./gradlew testDebugUnitTest` grün, `core-data` behält das Plugin.

- [x] **A7 · Test-Namen angleichen** 🟢 (erledigt 2026-06-02, mit B2)
  `PinningHostKeyVerifierTest` entfiel — der konsolidierte Test heißt
  einheitlich `TofuHostKeyVerifierTest` in `core-ssh`.

---

## Phase 2 — Tests konsolidieren

- [x] **B2 · core-ssh-Tests an ihren Ort** 🟡 (erledigt 2026-06-02)
  - `SshSecurityTest`, `SshKeygenTest`, `BcOpenSshKeyProviderTest`,
    `PathQuoteTest` von `app-lobber/src/test` → `core-ssh/src/test`.
  - Die **drei** `TofuHostKeyVerifier`-Tests (lobber/caster/prodder) zu **einem**
    Test in `core-ssh/src/test` zusammenführen, App-Kopien löschen.
  *Akzeptanz:* `core-ssh/src/test` nicht mehr leer; `./gradlew test` grün;
  keine doppelten Verifier-Tests mehr in den Apps.

---

## Phase 3 — App-agnostische SSH-Logik nach `core-ssh`

- [x] **A4 · `screen -ls`-Parsing nach core** 🟡 (erledigt 2026-06-02)
  Gemeinsame Parse-Funktion in `core-ssh` (token-Extraktion aus `screen -ls`).
  Caster (`parseRunningSessions`) und Prodder (`parseSessions`) bauen ihre
  app-spezifischen Typen (`ProjectEntry`/`ScreenSession`) auf dem core-Ergebnis
  auf.
  *Akzeptanz:* eine Quelle der Wahrheit für die Token-Zerlegung; bestehende
  Parser-Tests grün.

- [ ] **B4 · `SshjClient`-Connection-Scaffold nach core** 🔴
  App-agnostisches Gerüst in `core-ssh`: Verifier setzen, `connectTimeout`,
  stdout+stderr nebenläufig drainen (`readCapped`), `cmd.join()`. Z. B.
  `withSshSession { … }` / ein Drain-Helper, der `Triple(exit, out, err)`
  liefert. Die drei `SshjClient` rufen das Scaffold, behalten aber ihre
  app-spezifischen Kommandos.
  *Akzeptanz:* `run()`-Drain-Logik existiert nur noch einmal; alle drei Clients
  nutzen sie; Hard Rule 3 (drainen vor `join`) bleibt eingehalten.

- [ ] **A3 · Learn-Callback-Signatur vereinheitlichen** 🟡
  Eine Variante festlegen (empfohlen: nicht-`suspend` `(String)->Unit`, im
  Scope der Aufrufer `launch`-gewrappt) und in allen drei Clients gleich
  anwenden. Fällt teilweise mit B4 zusammen.

---

## Phase 4 — Geteilte UI nach `core-ui`

- [x] **B1 · Settings-/Server-UI nach core-ui** 🔴 (erledigt 2026-06-02)
  Stateless Composables in `core-ui` (`SettingsComponents.kt`): `ServerRow`,
  `ServerEditor` (workingDir optional via nullable Label), `ServerPicker`,
  `StatusDot`, `KeyField` — reine Daten + Callbacks, keine VM-Kopplung. Strings
  liegen als `cu_…` in core-ui (EN+DE). Die drei Apps rufen sie; ihre lokalen
  Kopien sind gelöscht. Die App-Screens bleiben Kompositions-Wurzel (Lobber
  behält ADB-Block/Onboarding). *Akzeptanz erfüllt.*

- [x] **A1 · Key-Sichtbarkeits-Toggle vereinheitlichen** 🟢 (erledigt 2026-06-02)
  Show/Hide-Toggle direkt in Caster + Prodder ergänzt (wie Lobber); geht bei
  B1 in das geteilte Key-Feld auf. *Akzeptanz erfüllt:* alle drei Apps haben
  denselben Toggle (Strings `action_show`/`action_hide`).

- [x] **A6 · Log-Rendering nach core-ui** 🟡 (erledigt 2026-06-02)
  `LogLineRow(line)` in `core-ui/LogView.kt` (eine `exit`-Darstellung). Lobber
  (Install + ADB-Log) und Caster (Launch) nutzen sie; die App-Kopien sind weg.
  core-ui hängt dafür an core-ssh (nur `LogLine`). *Akzeptanz erfüllt.*

- [x] **A8 · DE-Lokalisierung nachziehen** 🟡 (erledigt 2026-06-02)
  `values-de/strings.xml` für alle drei Apps + core-ui (geteilte `cu_`-Strings).
  Geteilte UI-Strings werden dank B1 nur einmal (in core-ui) gepflegt.
  *Akzeptanz erfüllt:* alle drei Apps rendern auf einem DE-Gerät deutsch.

- [x] **A5 · Fingerprint-Preserve-Logik vereinheitlichen** 🟢 (erledigt 2026-06-02)
  Eine Helper-Funktion (z. B. in `core-data` an `ServerProfile`): „Pin behalten,
  solange host+port unverändert". Alle drei `SettingsViewModel.saveServer()`
  rufen sie.
  *Akzeptanz:* die Logik existiert einmal; bestehende VM-Tests grün.

---

## Phase 5 — Robustheit & Angleichung

- [ ] **A2 · Host-Key-Persistenz vereinheitlichen** 🟡
  - Caster/Prodder einen `applicationScope` geben (wie `LobberApplication`).
  - Persistenz-Wiring in allen drei Apps an dieselbe Stelle legen (empfohlen:
    Factory, wie Lobber) und über `applicationScope` laufen lassen, damit der
    Pin auch bei VM-Clear geschrieben wird.
  *Akzeptanz:* identisches Wiring-Muster in allen drei Apps; Pin-Schreiben hängt
  nicht mehr am `viewModelScope`.

---

## Nicht-Ziele (bewusst offen gelassen)

- `SshConfig`, `SshClient`, `SshjClient`, `resolveConfig()` bleiben pro App
  (Hard Rule 1, AUDIT §C).
- Build-Files (außer B3), Manifeste, `KeyVault`, `SettingsStore`, `ConfigState`
  sind konsistent — kein Handlungsbedarf (AUDIT §D).
