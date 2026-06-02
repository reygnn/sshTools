# sshTools — Source-Audit

**Datum:** 2026-06-02
**Versionen:** Lobber 0.6.1 (29), Caster 0.5.1 (9), Prodder 0.2.1 (6)
**Umfang:** alle drei Apps (Lobber, Caster, Prodder) + vier `core-*`-Module,
Build-Konfiguration, String-Ressourcen, Test-Inventar.
**Ziel:** Drift zwischen den drei Apps aufdecken und Verbesserungen benennen.
**Leitplanke:** Hard Rule 1 (`core-ssh` hält nur app-agnostische Primitive;
`SshConfig`/`SshClient`/`SshjClient`/`resolveConfig()` bleiben pro App). Jede
Empfehlung hält diese Regel ein — wo Code geteilt werden könnte, geschieht das
in `core-data`/`core-ui`, **nie** durch Hochziehen von App-SSH-Code in `core-ssh`.

> **Hinweis:** Diese Datei ersetzt ein älteres Audit (Stand vor den Refactor-Commits
> `40fa166`/`b4c2af5`/`f333472`). Dessen Funde (vereinheitlichte Host-Key-Persistenz,
> `runCommand`/Drain nach core-ssh, `parseScreenSessions`, `KeyField`/`StatusDot`/
> `LogLineRow` in core-ui, core-ssh-Tests, entferntes Serialization-Plugin, `values-de/`)
> sind inzwischen umgesetzt. Die folgenden Funde betreffen den **aktuellen** Stand.

---

## Gesamtbild

Die Codebasis ist gesund. Die Modul-Aufteilung trägt, die Hard Rules sind
durchgängig respektiert (TOFU-Host-Key-Pinning pro Profil, eine Connection pro
Operation, `KeyVault` at-rest, Interface-basierte Clients + `createClient`-Factory,
kein `kotlin.android`-Plugin), und die Kernmodule sind sorgfältig dokumentiert.
Build-Dateien und ViewModel-Skelette sind über die drei Apps hinweg bemerkenswert
konsistent — die frühere Drift ist sichtbar aufgeräumt.

Der **verbliebene Drift-Vektor ist Copy-Paste-Parallelität**: identische
ViewModel-/UI-Logik liegt in drei Kopien nebeneinander, und genau dort schleichen
sich die kleinen Inkonsistenzen ein (Satzzeichen, Helfer mal da/mal nicht,
Lifecycle-Verdrahtung). Es gibt **keine kritischen Bugs**; die Funde sind
Wartbarkeit, Konsistenz und ein paar gemeinsame Robustheits-Verbesserungen.

Priorisierte Übersicht — **alle Funde abgearbeitet** (Umsetzung 2026-06-02,
Commits `c638f70` … `9dde824` auf `main`; Status je Fund siehe letzte Spalte):

| # | Fund | Typ | Schwere | Hard-Rule-1-konform? | Status |
|---|------|-----|---------|----------------------|--------|
| 1 | `SettingsViewModel`-Server-CRUD 3× dupliziert | Duplikat | **Hoch** | ja (Ziel: core-data, nicht core-ssh) | ✅ `05d2808` |
| 2 | SSH-Fehlertexte hartkodiert Deutsch, umgehen `UiText.Resource`/Lokalisierung | Drift | **Mittel** | ja | ✅ `10a4f7d` |
| 3 | Lifecycle-Verdrahtung Lobber ≠ Caster/Prodder | Drift | Mittel | ja | ✅ `5fa38a9` |
| 4 | `ServerSelection` + `serverSelection`/`selectServer` 3× dupliziert | Duplikat | Mittel | ja | ✅ `8a54d7e` |
| 5 | Streaming-Pfade ohne Byte-Cap + unbegrenztes Log-Wachstum im VM-State | Robustheit | Mittel | ja | ✅ `10a4f7d` |
| 6 | `stringRes()`-Helfer in Caster+Prodder dupliziert, Lobber abweichend | Drift | Niedrig | ja | ✅ `9dde824` |
| 7 | `error_invalid_port` ohne Punkt in Prodder (EN+DE) | Drift | Niedrig | ja | ✅ `c638f70` |
| 8 | `testImplementation(libs.turbine)` redundant in allen Apps | Hygiene | Niedrig | ja | ✅ `c638f70` |
| 9 | `resolveConfig()` kosmetisch uneinheitlich (Doc/Var) | Drift | Niedrig | **bewusst dupliziert** | ⏸ bewusst belassen (Hard Rule 1) |
| 10 | Prodder `ScreenSession` ≙ core `ScreenSessionInfo` (1:1-Mapping) | Duplikat | Niedrig | ja | ✅ dokumentiert `9dde824` |
| 11 | Lobber `AdbStatusDot` statt core-ui `StatusDot` | Drift | Niedrig | wahrscheinlich bewusst | ✅ dokumentiert `9dde824` |
| 12 | Doc-/Kommentar-Drift (`applicationScope`, FLAG_SECURE) | Kosmetik | Niedrig | ja | ✅ `c638f70` |

Bewusst **nicht** geändert: #9 (`resolveConfig()` bleibt pro App — Hard Rule 1)
und die gemeinsamen Label-Strings aus Abschnitt B (`back`/`done`/`saving`/
`settings_*` — benigne Duplizierung, behält je App die Freiheit zum Umbenennen).

> Die folgenden Abschnitte sind der **ursprüngliche Analyse-Snapshot**
> (Beschreibung + Empfehlung je Fund). Der Umsetzungsstand steht in der Tabelle
> oben; die `file:line`-Verweise zeigen auf den Stand *vor* der Umsetzung.

---

## A. Echter Drift — sollte konvergieren

### 1. Server-CRUD ist in drei `SettingsViewModel`s dupliziert  — *Schwere: hoch*

`app-caster/.../ui/SettingsViewModel.kt`, `app-prodder/.../ui/SettingsViewModel.kt`,
`app-lobber/.../ui/SettingsViewModel.kt`.

`ServerForm`, `SettingsUiState`, `configState`, `savedEvents`, der `init`-Block und
die komplette Editor-/Persistenz-Logik (`addServer`, `editServer`, `cancelEdit`,
`onEditName/Host/Port/Username[/WorkingDir]`, `updateForm`, `saveServer`,
`deleteServer`, `done`) sind über die drei Apps hinweg **nahezu Byte-identisch**.

- **Caster ↔ Prodder**: unterscheiden sich *nur* durch das `workingDir`-Feld in
  `ServerForm` und einen Validierungsteil in `saveServer` — sonst gleich.
- **Lobber**: dasselbe CRUD + zusätzlich der ADB-Block.

Das ist der größte Drift-Risiko-Herd der Codebasis: eine Validierungs- oder
Fehlerbehandlungs-Änderung muss dreimal von Hand nachgezogen werden — und genau so
ist die Inkonsistenz aus Fund #7 entstanden.

**Hard Rule 1:** Das ist **kein** SSH-Code. Die Logik operiert auf `ServerProfile`
(`core-data`) und der bereits geteilten `ServerProfile?.pinToKeepFor(...)`. Eine
Konsolidierung verletzt Hard Rule 1 nicht, *solange sie nicht nach `core-ssh`
wandert*.

**Empfehlung (abgewogen):** Die Apps halten ViewModels bewusst getrennt (manuelle
DI, eigene Tests) — also **kein** gemeinsamer ViewModel-Basistyp. Stattdessen einen
reinen, Android-freien State-Holder in `core-data` extrahieren, z. B.
`ServerListEditor` (hält `servers` + `editing: ServerForm?`, kann
`startEdit/update/save/delete` und liefert validierte Ergebnisse oder einen
Fehler-Enum). `workingDir` wird per Flag/Policy parametrisiert. Die drei
ViewModels delegieren dann nur noch und bleiben dünn. Der CRUD-Kern wird damit
**einmal** in `core-data` getestet; die VM-Tests prüfen nur noch die Verdrahtung.
Falls eine Extraktion zu schwer wiegt: zumindest Caster und Prodder (die sich nur
um `workingDir` unterscheiden) zusammenführen.

### 2. SSH-Fehlertexte sind hartkodiert Deutsch und umgehen die Lokalisierung — *Schwere: mittel*

`app-caster/.../ssh/SshjClient.kt:38` („Projektsuche fehlgeschlagen …"),
`app-lobber/.../ssh/SshjClient.kt:35` („find fehlgeschlagen …"),
`app-lobber/.../ssh/SshjBootstrap.kt:35,42`, diverse `require(...)`-Meldungen
(„Ungültiger Projektname", „Ungültige Session-ID").

Diese Strings werden über `e.message?.let(UiText::Literal)` direkt in die UI
gereicht (`LaunchViewModel.kt:124`, `InstallViewModel.kt:72/99`, etc.). Damit
entsteht eine zweigleisige Lokalisierungsstrategie: die meisten Texte laufen über
`strings.xml` (EN) + `values-de/` (DE) via `UiText.Resource`, aber die
SSH-Schicht emittiert **hartkodiertes Deutsch**, das im englischen Locale
unverändert erscheint. Das widerspricht der Localization-Linie in `CLAUDE.md`
(UI-Strings über Ressourcen, neue Texte Englisch) und ist zwischen den Apps zwar
konsistent *deutsch*, aber konsistent *am Lokalisierungspfad vorbei*.

**Empfehlung:** Fehlerklassen statt freier deutscher Strings — die SSH-Schicht
wirft typisierte Exceptions (z. B. `ProjectListFailed(exit, stderr)`), die VM
mappt sie auf `UiText.Resource(...)`. Mindestens: die hartkodierten Texte auf
Englisch ziehen (Konvention für neue/umgeschriebene Zeilen) und prüfen, ob ein
roher `stderr`-Auszug überhaupt in die UI soll.

### 3. Lifecycle-Verdrahtung: Lobber weicht von Caster/Prodder ab — *Schwere: mittel*

- **Caster** (`MainActivity.kt:25,44-47`) und **Prodder** (`MainActivity.kt:30-32,50-53`):
  VM via `by viewModels { factory }`, NavHost inline in `onCreate`, „bei Foreground
  laden / bei Background leeren" über
  `LifecycleResumeEffect { load(); onPauseOrDispose { clear() } }`.
- **Lobber** (`MainActivity.kt:52-69`): VM via `viewModel(factory=)` in einem
  `@Composable LobberApp`, und „bei Background leeren" über einen manuellen
  `LifecycleEventObserver` auf `ON_STOP` (`clearAabs()`). Ein symmetrisches
  Reload-on-Resume wie bei Caster/Prodder ist hier **nicht** verdrahtet.

Drei Konsequenzen: (a) zwei verschiedene VM-Beschaffungs-Stile, (b) zwei
verschiedene „clear on background"-Mechanismen, (c) **möglicher Verhaltensunterschied** —
Lobber lädt die AAB-Liste nach Rückkehr aus dem Hintergrund evtl. nicht
automatisch neu, während Caster/Prodder das tun. **Zu verifizieren** (ggf. lädt
`InstallerScreen` selbst über einen eigenen Effekt nach).

**Empfehlung:** Auf das `LifecycleResumeEffect`-Muster konvergieren (load on
resume / clear on pause), auch in Lobber. Das beseitigt sowohl die
Mechanismus-Divergenz als auch den potenziellen Reload-Gap.

### 7. `error_invalid_port` ohne abschließenden Punkt in Prodder — *Schwere: niedrig*

`app-prodder/.../res/values/strings.xml:21` und `values-de/strings.xml:21`
schreiben „… 65535" / „… 65535 liegen" **ohne** Punkt; Caster und Lobber haben
in beiden Locales den Punkt. Reiner Konsistenzfehler — klassisches Symptom des
Copy-Paste-Drifts aus Fund #1. **Empfehlung:** Punkt in Prodder ergänzen (EN+DE).

### 9. `resolveConfig()` kosmetisch uneinheitlich — *Schwere: niedrig (bewusst dupliziert)*

`app-{caster,prodder,lobber}/.../ssh/SettingsStoreExt.kt`. Die Funktionskörper
sind identisch; Lobber hat zusätzlich einen Doc-Kommentar und eine
`profile`-Zwischenvariable, Caster/Prodder inlinen.

**Wichtig:** Diese Duplizierung ist **per Hard Rule 1 gewollt** — `resolveConfig()`
liefert das app-eigene `SshConfig` (Prodder ohne `workingDir`) und darf nicht nach
`core-ssh`. Hier ist **nichts zu konsolidieren**. Einzig die kosmetische Abweichung
(Doc/Var) könnte man angleichen, wenn man die Dateien ohnehin anfasst. Bewusst als
„nicht anfassen außer kosmetisch" gelistet, damit das Audit nicht zur falschen
Refactoring-Idee verleitet.

### 11. Lobber `AdbStatusDot` statt core-ui `StatusDot` — *Schwere: niedrig*

Caster und Prodder nutzen `StatusDot` aus core-ui (fixes Grün, MATERIAL3RULES-A5-
Ausnahme); Lobber rollt für den ADB-Status einen eigenen Punkt mit
`.primary`/`.error` aus. Das ist **vermutlich bewusst** (ADB-an/aus ist eine andere
Semantik als „Session läuft"), sollte aber als bewusste Abweichung festgehalten
werden — sonst liest es sich beim nächsten Blick wie versehentlicher Drift.
**Empfehlung:** entweder `StatusDot` um einen Farb-Parameter erweitern (core-ui)
und Lobber darauf umstellen, oder einen Ein-Zeilen-Kommentar an `AdbStatusDot`,
der die Abweichung begründet.

### 12. Doc-/Kommentar-Drift — *Schwere: niedrig (kosmetisch)*

- `applicationScope` trägt in Caster/Prodder eine KDoc, in
  `LobberApplication.kt:14` nicht.
- Der FLAG_SECURE-Kommentar lautet bei Caster „Log-Output", bei Prodder
  „Terminal-Output", bei Lobber fehlt er. Intent identisch, Wortlaut driftet.

---

## B. Duplizierung / DRY-Kandidaten (Hard-Rule-1-konform)

### 4. `ServerSelection` + Picker-Verdrahtung 3× dupliziert — *Schwere: mittel*

`LaunchViewModel.kt` (Caster), `SessionsViewModel.kt` (Prodder),
`InstallViewModel.kt` (Lobber) definieren jeweils ein identisches
`data class ServerSelection(servers, selectedIndex)` plus den identischen
`serverSelection`-StateFlow (`combine(servers, selectedIndex){ … coerceIn … }`)
und das identische `selectServer(index)` (setSelectedIndex → reload).

**Hard Rule 1:** kein SSH-Code; `ServerProfile` ist core-data. **Empfehlung:**
`ServerSelection` + eine kleine `serverSelectionFlow(scope, settings)`-Helper-
Funktion nach core-data ziehen; der reload-Callback bleibt app-spezifisch und
wird als Parameter übergeben.

### 10. Prodder `ScreenSession` ≙ core `ScreenSessionInfo` — *Schwere: niedrig*

`app-prodder/.../ssh/SshClient.kt:19` definiert `ScreenSession(id,name,attached)`,
strukturell identisch zu `core-ssh/.../ScreenSessions.kt`'s `ScreenSessionInfo`;
`parseSessions` (`SshjClient.kt:63`) mappt 1:1 Feld für Feld.

Vermutlich bewusste Grenze (UI-Typ ≠ core-Typ, analog zu Casters eigenem
`ProjectEntry`). Wenn ja: stehen lassen, low priority. Wenn nicht: Prodder könnte
`ScreenSessionInfo` direkt verwenden und den 1:1-Mapper streichen. Caster mappt
dagegen `ScreenSessionInfo` auf eine *abgeleitete* Sicht (Namens-Set) — dort ist
die Trennung klar gerechtfertigt.

### 6. `stringRes()`-Helfer dupliziert — *Schwere: niedrig*

Caster (`Screens.kt:316-317`) und Prodder (`Screens.kt:377-378`) definieren je
einen identischen `stringRes(id, vararg)`-Wrapper um `stringResource`; Lobber nutzt
direkt `stringResource`. **Empfehlung:** den Helfer nach core-ui ziehen
(`@Composable fun stringRes(...)`) und alle drei darauf vereinheitlichen — oder
überall direkt `stringResource` verwenden.

### Gemeinsame String-Keys ohne core-ui-Heimat

`back`, `done`, `saving`, `open_settings`, `settings_title`, `settings_servers`,
`settings_key`, `add_server` sind in allen drei `values/strings.xml` identisch
vorhanden, aber **nicht** im `cu_`-Namespace von core-ui (wo bereits
`cu_cancel`, `cu_edit`, `cu_field_*` etc. wohnen). Kandidaten zur Zentralisierung
als `cu_*`, analog zu den schon geteilten Keys. Niedrige Priorität, aber es zieht
denselben Drift-Vektor wie #7 zusammen.
*(Hinweis: Prodder definiert bewusst kein eigenes `cancel` — der Editor-Cancel läuft
über core-uis `cu_cancel`; das ist korrekt, kein Fund.)*

---

## C. Robustheit (über alle Apps gleich — gemeinsame Verbesserung)

### 5. Streaming ohne Byte-Cap + unbegrenztes Log-Wachstum im VM-State — *Schwere: mittel*

Die Streaming-Pfade `SshjClient.startStreaming` (Caster, `:59-60`),
`SshjClient.executeStreaming` (Lobber, `:61-68`) und der ADB-Stream lesen via
`bufferedReader().lineSequence()` **ohne** Byte-Cap — anders als `runCommand`, das
über `readCapped(maxOutputBytes)` begrenzt.

- **Deadlock:** unkritisch — Hard Rule 3 ist eingehalten, stdout *und* stderr
  werden nebenläufig (`launch`) gedrained und vor `cmd.join()` verbunden.
- **Speicher:** Die VMs akkumulieren `log = current.log + line` **ohne Obergrenze**
  (`LaunchViewModel.kt:216-221`, `InstallViewModel.kt:100`,
  `SettingsViewModel.kt:189` für ADB). Ein sehr gesprächiges oder lang laufendes
  Kommando lässt die `List<LogLine>` im UI-State unbegrenzt wachsen.

**Empfehlung:** gemeinsames Cap/Ring-Puffer für die Log-Liste (z. B. die letzten
N Zeilen behalten) — einmal zentral, da das Muster in allen drei Apps identisch
ist. Optional auch im Streaming selbst eine Zeilen-/Byte-Obergrenze.

---

## D. Build- & Abhängigkeits-Hygiene

### 8. Redundantes `testImplementation(libs.turbine)` — *Schwere: niedrig*

Alle drei App-Builds deklarieren `testImplementation(project(":core-testing"))`
**und** `testImplementation(libs.turbine)`. `core-testing` exponiert Turbine
(zusammen mit junit/mockk/coroutines-test) bereits via `api(...)`, also ist die
explizite Turbine-Zeile transitiv schon vorhanden. **Empfehlung:** die
`libs.turbine`-Zeile in den drei App-Builds streichen.

### Weiteres (in Ordnung, nur zur Notiz)

- App-Builds nutzen `kotlin { jvmToolchain(21) }`, core-Module
  `compilerOptions { jvmTarget = JVM_21 }` — innerhalb der jeweiligen Ebene
  konsistent, kein Handlungsbedarf.
- Die drei App-`build.gradle.kts` sind bis auf `namespace`/`applicationId`/
  `versionCode`/`versionName` identisch — vorbildlich, kein Drift.
- `core-ui` hat kein `testOptions { … }` — irrelevant, da ohne Tests.

---

## E. Was gesund ist (bewusst nicht anfassen)

- **Hard Rules durchgängig respektiert:** TOFU-Pinning per Profil
  (`TofuHostKeyVerifier` + `learnHostFingerprint`, 3-arg), eine Connection pro
  Operation (`connectWithKey` → `.use {}`), `KeyVault` AES-256-GCM at-rest mit
  Legacy-PEM-Fallback, Clients strikt hinter Interface + `createClient`-Factory,
  kein `kotlin.android`-Plugin.
- **Host-Key-Persistenz einheitlich** über `applicationScope` in allen drei
  Factories — die frühere Drift (eigener Scope vs. `viewModelScope`) ist behoben.
- **Kernmodule sorgfältig dokumentiert** (`SettingsStore`, `KeyVault`,
  `ServerProfile`, `BcOpenSshKeyProvider`) inkl. der „geteilter Code, nicht
  geteilte Daten"-Begründung.
- **Test-Abdeckung breit und paritätisch:** jede VM hat Tests, core-ssh-Primitive
  (PathQuote, ScreenSessions, Keygen, BcOpenSshKeyProvider, TofuHostKeyVerifier,
  SshSecurity) liegen jetzt korrekt unter `core-ssh/src/test`.
- **`ServerProfile.pinToKeepFor`** und **`parseScreenSessions`** sind die Vorbilder,
  wie geteilte Logik korrekt in core landet (von Caster *und* Prodder genutzt,
  damit beide nicht driften können) — die Funde #1/#4 sollten diesem Muster folgen.

---

## Umsetzungs-Chronik (2026-06-02)

In der empfohlenen Reihenfolge abgearbeitet, jeder Schritt auf eigenem Branch,
mit grünen Unit-Tests + `lintDebug`, fast-forward nach `main`:

1. **#7, #8, #12** — Trivial-Fixes (Punkt, redundante Dep, Doc) → `c638f70`.
2. **#2 + #5** — typisierte `RemoteCommandException` + zentrales `Throwable.toUiText()`
   (core-ui), Log-Cap `plusCapped`/`DEFAULT_MAX_LOG_LINES` (core-ssh) → `10a4f7d`.
3. **#3** — Lobber-Lifecycle auf das `LifecycleResumeEffect`-Muster konvergiert
   (Reload-Gap verifiziert: existierte nicht) → `5fa38a9`.
4. **#4** — `ServerSelection` + `serverSelectionState(scope)` nach core-data → `8a54d7e`.
5. **#1** — `ServerForm` + `validate()` + `toForm()`/`upsert()` nach core-data;
   die VMs delegieren, Verhalten/Tests unverändert → `05d2808`.
6. **#6, #10, #11** — `stringRes`-Wrapper entfernt; `ScreenSession`/`AdbStatusDot`
   als bewusste Abweichungen dokumentiert → `9dde824`.

Abweichungen vom ursprünglichen Plan: #2 und #5 wurden zusammen umgesetzt (gemeinsame
VM-Dateien); statt eines `ServerListEditor`-Vollobjekts wurde die *Logik* (Validierung,
Pin-Erhalt, Upsert) extrahiert und die triviale State-Plumbing bewusst pro VM belassen
— gleiche Drift-Reduktion ohne Screen-/Test-Churn.
