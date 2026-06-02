# Source-Audit sshTools — Drift & Core-Konsolidierung

**Datum:** 2026-06-02
**Versionen zum Zeitpunkt:** Lobber 0.6.1 (29), Caster 0.5.1 (9), Prodder 0.2.1 (6)
**Scope:** Vergleich der drei App-Module (Lobber, Caster, Prodder) über alle
vergleichbaren Schichten — Settings-VM/-Screen, `SshjClient`, `SshClient`,
`SettingsStoreExt`, ViewModelFactories, Application-Klassen, `core-*`,
Build-Files, Manifeste, Tests.

**Ziel des Audits:** Gemeinsamkeiten der drei Programme identifizieren, die
nach `core-*` gehören, um künftige Drift zu vermeiden.

---

## Gesamtbild

Die Architektur ist sauber, und die Hard Rules aus `CLAUDE.md` werden
eingehalten (Interface-Client, ein Connect pro Operation, Key verschlüsselt at
rest, TOFU pro Profil, kein `kotlin.android`-Plugin). Die gefundene Drift ist
fast durchweg **Stil- und Struktur-Drift bei identischer Aufgabe**, plus eine
echte (wenn auch seltene) Robustheits-Lücke. **Kein funktionaler Bug.**

Die Drift entsteht strukturell dadurch, dass identische Logik dreimal
nebeneinander gepflegt wird, statt einmal in `core-*`. Genau hier setzt die
Konsolidierung an.

---

## A. Drift — gleiche Aufgabe, divergierende Umsetzung

### A1. Private-Key-Sichtbarkeit (Toggle nur in Lobber)
Lobber hat einen Show/Hide-Toggle am Key-Feld; Caster und Prodder maskieren
hart ohne Toggle.

| App | Stelle | Verhalten |
|-----|--------|-----------|
| Lobber  | `app-lobber/.../ui/Screens.kt:139-150`  | `keyVisible`-Toggle |
| Caster  | `app-caster/.../ui/Screens.kt:317-322`  | nur `PasswordVisualTransformation()` |
| Prodder | `app-prodder/.../ui/Screens.kt:362-367` | nur `PasswordVisualTransformation()` |

→ Vereinheitlichen; entfällt automatisch, sobald das Key-Feld eine geteilte
Composable in `core-ui` ist (siehe B1).

### A2. Host-Key-Persistenz an drei verschiedenen Stellen verdrahtet
Dasselbe „Fingerprint lernen → `learnHostFingerprint`" lebt in jeder App
woanders:

| App | Wo verdrahtet | Scope |
|-----|---------------|-------|
| Lobber  | Factory (`LobberViewModelFactory.kt:19-21`) | **`applicationScope`** |
| Caster  | VM-Default-Argument (`LaunchViewModel.kt:82-84`) | `viewModelScope` |
| Prodder | VM nullable Property (`SessionsViewModel.kt:60-64`, `SessionViewModel.kt:54-58`) | `viewModelScope` |

**Robustheits-Lücke:** Lobber persistiert über `applicationScope` (überlebt
VM-Clear). Caster/Prodder nutzen `viewModelScope` — verlässt der Nutzer den
Screen genau im Moment des Lernens, kann die DataStore-Schreiboperation
gecancelt werden und der Pin bleibt ungespeichert. Caster/Prodder besitzen gar
keinen App-Scope (`CasterApplication`/`ProdderApplication` haben keinen, nur
`LobberApplication.applicationScope`).

### A3. `SshjClient`-Interna divergieren
- Caster/Prodder haben einen `SSHClient.run()`-Helper; Lobber inlined
  `startSession/exec/readCapped` dreimal (`app-lobber/.../SshjClient.kt:34-94`).
- Learn-Callback: Caster `suspend (String)->Unit` + Deferral via
  `AtomicReference`/`connected{}`; Prodder/Lobber `(String)->Unit` direkt im
  Verifier.
- `readCapped`/`join`-Reihenfolge: Caster `join()` dann `await()`;
  Prodder/Lobber `await()` dann `join()` (beide korrekt, da nebenläufig
  gedraint — aber inkonsistent).
- `connect()`: Lobber setzt `connectTimeout` vor dem Verifier, die anderen
  danach.

### A4. `screen -ls`-Parsing dupliziert
`parseRunningSessions` (`caster/.../SshjClient.kt:117`) und `parseSessions`
(`prodder/.../SshjClient.kt:84`) — die Token-Zerlegung ist Zeile für Zeile
identisch (`.substringBefore('\t').substringBefore(' ')…`).

### A5. Fingerprint-Preserve-Logik in `saveServer()` in drei Stilen
Gleiche Semantik (Pin behalten, solange host+port unverändert), drei
Schreibweisen:
- Lobber `takeIf{}`-Kette (`SettingsViewModel.kt:116-119`)
- Caster `keepFingerprint = …takeIf` (`SettingsViewModel.kt:109-113`)
- Prodder `keepPin`-Boolean (`SettingsViewModel.kt:109-116`)

### A6. Log-Rendering divergiert
Gleiche `LogLine`-Daten, zwei Darstellungen:
- Lobber: `LogLineRow` mit `"─── exit 0 ───"` in `LazyColumn`
  (`Screens.kt:560-577`)
- Caster: `LogText` mit `"exit: $code"` in `Surface`+`Column`+`verticalScroll`
  (`Screens.kt:233-262`)

### A7. Kleinkram
- `stringRes`-Wrapper in Caster/Prodder, direktes `stringResource` in Lobber.
- `StatusDot` mit `Color(0xFF2E7D32)` dupliziert in Caster und Prodder (Prodder
  kommentiert die M3-Ausnahme, Caster nicht).
- Test-Name: Prodder `PinningHostKeyVerifierTest` (alter Name) vs.
  Caster/Lobber `TofuHostKeyVerifierTest`.

---

## B. Konsolidierung nach `core-*` (Kern des Ziels)

### B1. Geteilte UI nach `core-ui` heben — größte Reuse-Chance
`SettingsScreen`, `ServerRow`, `ServerEditor`, `ServerPicker`, `StatusDot`,
`stringRes` sind über alle drei Apps quasi wörtlich kopiert (Caster ↔ Prodder
fast byte-identisch; Lobber gleicht bis auf den ADB-Block). `LogLine`-Rendering
(A6) gleich mit als eine geteilte Composable.

**Hürde:** Die Settings-UI hängt an app-spezifischen `SettingsViewModel`s und
`ServerForm`s, die sich nur in `workingDir` unterscheiden (Prodder hat keins).
Konsolidierung braucht entweder ein gemeinsames `ServerForm`/Callback-Interface
in core oder stateless Composables, die reine Callbacks + Daten nehmen
(bevorzugt: keine VM-Kopplung in `core-ui`).

### B2. Test-Platzierung korrigieren
`core-ssh/src/test` ist **leer**; die Tests für core-ssh-Primitives
(`SshSecurityTest`, `SshKeygenTest`, `BcOpenSshKeyProviderTest`, `PathQuoteTest`)
liegen alle unter `app-lobber`. `TofuHostKeyVerifier` (lebt in `core-ssh`) wird
**dreimal eigenständig** getestet (`app-lobber`, `app-caster`, `app-prodder`) —
drei unterschiedliche Implementierungen desselben Vertrags, einer mit altem
Namen.

→ Ein Test pro core-Primitive in `core-ssh/src/test`, die App-Kopien löschen.

### B3. Totes `kotlin.serialization`-Plugin entfernen
Das Plugin ist in allen drei App-`build.gradle.kts` angewandt, aber **kein
App-Code nutzt `@Serializable`** — die Klasse (`ServerProfile`) lebt in
`core-data`, das das Plugin selbst anwendet (verifiziert). Lobber deklariert
zusätzlich ein überflüssiges explizites `kotlinx.serialization.json` (transitiv
via core-data verfügbar).

→ Plugin aus den drei App-Modulen entfernen, Lobbers json-Dep entfernen.

### B4. `SshjClient`-Connection-Scaffold nach `core-ssh`
Connect/`run()`/Drain-Logik (Verifier setzen, Timeout, stdout+stderr drainen,
`join`) ist app-agnostisch und redundant über die Apps. Hard Rule 1 verbietet
zu Recht das Hoisten von `SshConfig`/`SshClient`/`SshjClient` — aber das
*Scaffold* (z. B. ein `withSshSession`/Drain-Helper) kann in `core-ssh` leben,
ohne die Regel zu verletzen. `screen -ls`-Parsing (A4) ebenfalls als
core-Funktion.

---

## C. Bewusst NICHT konsolidieren (Hard Rule 1)

`SshConfig`, das `SshClient`-Interface, `SshjClient` und `resolveConfig()`
bleiben pro App — die Apps unterscheiden sich fachlich (Prodder ohne
`workingDir`, Lobber installiert AABs, Caster fährt `screen`). Das ist
dokumentierte Absicht, kein Drift.

---

## D. Konsistent — kein Handlungsbedarf

- Build-Files (außer B3): SDK 36, JDK 21, AGP-Plugins, Dependencies,
  `packaging`-Excludes identisch.
- Manifeste: `allowBackup=false`, `usesCleartextTraffic=false`,
  einzige Permission `INTERNET`, gleiche Activity-Struktur.
- `KeyVault`, `SettingsStore`, `ConfigState`, `SshSecurity.installBouncyCastle()`
  (in allen drei `Application.onCreate`).
