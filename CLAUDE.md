# CLAUDE.md

Project conventions for **sshTools** — three SSH build-host tools
(**Lobber**, **Caster**, **Prodder**) merged into one Gradle build that
shares a set of `core-*` modules. Claude Code reads this file automatically
at session start. Keep it short and actionable — not a marketing
description (that's `README.md`).

For the working agreement, see `WHY_CLAUDE.md`. For Material 3 rules, see
`MATERIAL3RULES.md`. For test conventions, see
`core-testing/src/main/java/com/github/reygnn/core/testing/TESTING_CONVENTIONS.kt`.

Cross-project conventions (stack baseline, git workflow, test philosophy,
code language) live in `~/.claude/CLAUDE.md` and load automatically. This
file only holds what is specific to sshTools.

---

## Scope

Three separate apps for driving a build host over SSH, each shipping as its
own APK/AAB (own `applicationId`, `versionCode`/`versionName`):

- **Lobber** (`app-lobber`) — remote AAB installer. Onboards a fresh SSH key
  by password, then streams `install-aab.sh <file>` over pubkey auth. Also
  the ADB-reconnect helper.
- **Caster** (`app-caster`) — project launcher. Lists build projects on the
  host and starts/stops `screen` sessions, streaming the launch log.
- **Prodder** (`app-prodder`) — session prodder. Attaches to running
  `screen` sessions, captures their content (`hardcopy`) and sends input
  (`stuff`).

The apps are deliberately separate deliverables. A new feature category
needs explicit user agreement before it is built.

---

## Stack particulars

Beyond the shared baseline (Kotlin + Compose + M3, SDK 36, JDK 21, AGP 9
built-in Kotlin, JUnit 4 + MockK + Turbine):

- **Multi-module by design.** Four `core-*` libraries
  (`core-data`, `core-ssh`, `core-ui`, `core-testing`) plus three app
  modules. The split is justified *only* because three apps share the SSH,
  crypto and persistence stack — it is the exception to the
  "single `:app` module" baseline, not a pattern to copy.
- **SSH:** sshj 0.40 + BouncyCastle 1.84 (`bcprov`/`bcpkix-jdk18on`),
  `slf4j-nop` at runtime. Android's stripped BC lacks Ed25519, so each app
  registers a full `BouncyCastleProvider` at slot 1 on startup
  (`SshSecurity.installBouncyCastle()`).
- **Persistence:** DataStore Preferences (`core-data/SettingsStore`).
- **Navigation:** `navigation-compose` (each app has its own `NavHost`).
- **No DI framework.** Manual DI via each app's `Application` class +
  a `…ViewModelFactory`.

---

## Architecture

```
core-data/      SettingsStore (DataStore), KeyVault (AES-256-GCM at rest),
                ServerProfile (@Serializable), ConfigState
core-ssh/       app-agnostic SSH primitives ONLY: SshKeygen, SshSecurity +
                TofuHostKeyVerifier + shell/pathQuote + hostKeyFingerprint,
                BcOpenSshKeyProvider, StreamUtils.readCapped, LogLine
core-ui/        AppTheme (Material You), UiText (deferred string resolution)
core-testing/   MainDispatcherRule, TESTING_CONVENTIONS (api deps: junit,
                mockk, coroutines-test, turbine)

app-<name>/     <Name>Application, MainActivity (NavHost), …ViewModelFactory
  ui/           Screens.kt + one ViewModel per screen
  ssh/          SshConfig, SshClient (interface), SshjClient (impl),
                resolveConfig() ext  — PER APP, see Hard rule 1
```

Data flow: `MainActivity` → `…ViewModelFactory` injects `SettingsStore` and a
`createClient` factory → ViewModel exposes a `StateFlow` UI state → `Screens`
render it. Each SSH operation opens, authenticates, runs, tears down.

---

## Hard rules

1. **`core-ssh` holds only app-agnostic primitives.** Anything app-shaped —
   `SshConfig`, the `SshClient` interface, `SshjClient`, `resolveConfig()` —
   lives in each app's own `ssh/` package, **not** in core. The apps differ:
   Prodder's `SshConfig` has no `workingDir`; Lobber's client installs AABs;
   Caster's runs `screen`. Don't hoist these into core to "share" them.

2. **`SshClient` is an interface, always.** ViewModels take a
   `createClient: (SshConfig) -> SshClient` factory (default `::SshjClient`)
   so tests inject a MockK client. Never reference `SshjClient` from a VM.

3. **One SSH connection per operation.** `SshjClient` opens, authenticates,
   runs, disconnects. No pool, no long-lived session — over a build LAN the
   TCP setup is cheap and a half-broken pooled connection costs far more.
   Always drain stdout *and* stderr (`readCapped`) before `cmd.join()` so a
   full channel buffer can't deadlock.

4. **One shared SSH keypair, encrypted at rest.** `filesDir/id_ed25519`
   holds Base64 of an AES-256-GCM ciphertext from `KeyVault` (non-exportable
   Android-Keystore key), mode `0600`. The key **and** the Keystore alias
   (`ssh_tools_key_vault`) are shared across all three apps, so onboarding
   once makes the key usable everywhere. A legacy plaintext PEM (`-----`
   prefix) is read as-is and re-encrypted on the next save. Don't move the
   key off `filesDir`.

5. **Host keys: trust-on-first-use, pinned per profile.**
   `TofuHostKeyVerifier` learns the `SHA256:…` fingerprint on first connect
   and pins it into `ServerProfile.knownHostFingerprint` (per profile, never
   global). Every later connect must match (constant-time compare) or the
   connection aborts hard — no prompt, no silent accept. The pin is persisted
   via `SettingsStore.learnHostFingerprint(host, port, fingerprint)` (3 args).

6. **AGP 9 built-in Kotlin — no external `org.jetbrains.kotlin.android`
   plugin in any module.** App modules apply only `kotlin.compose`
   (+ `kotlin.serialization` where needed); library modules apply
   `com.android.library` (+ compose/serialization where needed). Re-adding
   `kotlin.android` (the Studio upgrade assistant likes to) breaks the new
   DSL — remove it again.

---

## Build & test

```bash
./gradlew bundleRelease                 # the deliverable: unsigned release AABs
./gradlew testDebugUnitTest             # all unit tests (JVM, no instrumentation)
./gradlew :app-caster:testDebugUnitTest # one app's tests
```

The Gradle wrapper (9.5.1) is bundled. Signing happens host-side in
`install-aab.sh` with the family key — never in Gradle (see `~/.claude/CLAUDE.md`).

---

## Test conventions

Full reference: `core-testing/.../TESTING_CONVENTIONS.kt`. Headlines:

- **`MainDispatcherRule` defaults to `UnconfinedTestDispatcher`** (eager).
  Coroutines launched on `Dispatchers.Main` run without an explicit
  `advanceUntilIdle()`. Always `runTest(rule.dispatcher)` — plain
  `runTest { }` spins up its own scheduler and tests go flaky.
- **Stub every flow the ViewModel reads at construction** before building it:
  `isConfigured`, `servers`, `selectedIndex`, and any `suspend` the init
  touches (`readKeyPem()`). Miss one and MockK throws in the VM constructor —
  distinctive symptom: **many VM tests go red at once** with the same error.
- The config comes from `SettingsStore.resolveConfig()` (an extension reading
  `servers` + `selectedIndex` + `readKeyPem()`), **not** a `config` flow.
  Mock the underlying members; you can't stub the extension directly.
- MockK only (no Mockito); Turbine + `expectMostRecentItem()` for StateFlow.

---

## Localization

Each app ships English (`res/values/strings.xml`) + German
(`res/values-de/strings.xml`); Composables resolve via
`stringResource(R.string.…)`. ViewModels emit `UiText` (`core-ui`) rather than
resolved strings, so the resource is resolved at render time. New user-facing
strings go in `strings.xml` from the start.

---

## Versioning

Each app's `versionName`/`versionCode` in `app-<name>/build.gradle.kts` is
independent and matches that app's GitHub release tag. Keep them aligned.

---

## What this file is NOT

- Not a description of the project (see `README.md`).
- Not a TODO list or changelog.
- Not the full testing reference (see `TESTING_CONVENTIONS.kt`).

Update this file when an architectural rule changes or a hard-won lesson
deserves to be future-proofed. Don't bloat it with what's obvious from the code.
