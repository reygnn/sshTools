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

- [ ] **B3 · Totes Serialization-Plugin entfernen** 🟢
  `alias(libs.plugins.kotlin.serialization)` aus `app-lobber`, `app-caster`,
  `app-prodder` `build.gradle.kts` entfernen; dazu Lobbers
  `implementation(libs.kotlinx.serialization.json)`.
  *Akzeptanz:* `./gradlew testDebugUnitTest` grün, `core-data` behält das Plugin.

- [ ] **A7 · Test-Namen angleichen** 🟢
  `PinningHostKeyVerifierTest` → einheitlich (entfällt mit B2, falls B2 zuerst).

---

## Phase 2 — Tests konsolidieren

- [ ] **B2 · core-ssh-Tests an ihren Ort** 🟡
  - `SshSecurityTest`, `SshKeygenTest`, `BcOpenSshKeyProviderTest`,
    `PathQuoteTest` von `app-lobber/src/test` → `core-ssh/src/test`.
  - Die **drei** `TofuHostKeyVerifier`-Tests (lobber/caster/prodder) zu **einem**
    Test in `core-ssh/src/test` zusammenführen, App-Kopien löschen.
  *Akzeptanz:* `core-ssh/src/test` nicht mehr leer; `./gradlew test` grün;
  keine doppelten Verifier-Tests mehr in den Apps.

---

## Phase 3 — App-agnostische SSH-Logik nach `core-ssh`

- [ ] **A4 · `screen -ls`-Parsing nach core** 🟡
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

- [ ] **B1 · Settings-/Server-UI nach core-ui** 🔴
  Stateless Composables in `core-ui` (reine Daten + Callbacks, **keine**
  VM-Kopplung): `ServerRow`, `ServerEditor`, `ServerPicker`, `StatusDot`,
  `stringRes`-Helfer und das maskierte Key-Feld.
  - Gemeinsames `ServerForm` / Callback-Vertrag, der `workingDir` optional
    behandelt (Prodder hat keins).
  - Pro App bleibt nur das App-Spezifische (Lobber: ADB-Block).
  *Akzeptanz:* `ServerRow`/`ServerEditor`/`ServerPicker`/`StatusDot` existieren
  genau einmal; Caster/Prodder-`Screens.kt` schrumpfen deutlich.

- [ ] **A1 · Key-Sichtbarkeits-Toggle vereinheitlichen** 🟢
  Im geteilten Key-Feld (B1) den Show/Hide-Toggle für alle drei Apps aktivieren.
  *Akzeptanz:* Lobber, Caster, Prodder haben denselben Toggle.

- [ ] **A6 · Log-Rendering nach core-ui** 🟡
  Eine `LogLine`-Composable in `core-ui` (eine `exit`-Darstellung, ein
  Scroll-Verhalten). Lobber (Install/ADB) und Caster (Launch) nutzen sie.
  *Akzeptanz:* `LogLine`-Rendering existiert einmal.

- [ ] **A5 · Fingerprint-Preserve-Logik vereinheitlichen** 🟢
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
