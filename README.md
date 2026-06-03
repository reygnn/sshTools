# sshTools

> Four small Android remotes for one trusted build host — **lob** a build at
> your phone, **cast** a session at the server, **prod** a stuck session along,
> **cull** what's piled up on it.

sshTools is the merged home of three sibling apps that used to live in separate
repos (Lobber, Caster, Prodder — now emptied redirects pointing here), plus a
fourth (**Culler**) born here on the same foundation. They share the same SSH
transport, crypto, persistence and test conventions — so they share **code**
through a set of `core-*` Gradle modules, while still shipping as independent
apps.

Each app is a thin, single-purpose remote for a **known build host** reachable
on the LAN or over a **Tailscale tailnet**. None of them is a generic SSH client
or meant for raw exposure on the open internet (see [Security](#security)).

---

## The apps

| App | Verb | What it does |
|---|---|---|
| **Lobber** | *lob a build at the phone* | Lists the AABs sitting on the build host and installs the tapped one by running `install-aab.sh` over SSH, streaming its live output. Also the ADB-reconnect helper. |
| **Caster** | *cast a session at the server* | Lists Claude Code projects (`claude_<name>.sh` scripts) on the host and starts/stops a detached `screen` session per project with one tap, streaming the launch log. |
| **Prodder** | *prod a stuck session along* | Lists every `screen` session, lets you peek at its current rendered screen (`hardcopy`) and send input — a line, Enter, or Ctrl-C — without attaching. |
| **Culler** | *cull what's piled up* | Lists the entries of one configured directory on the host and deletes the tapped one after a confirmation dialog — recursively (`rm -rf`) for directories, plain `rm` for files. |

All four drive the host over short-lived, per-operation SSH connections (no
pool, no persistent PTY) authenticated with an Ed25519 key. The transport is
[sshj](https://github.com/hierynomus/sshj); because Android's bundled
BouncyCastle is stripped of the algorithms sshj needs to load Ed25519 keys, each
app installs a full `BouncyCastleProvider` at JCE slot 1 on startup
(`SshSecurity.installBouncyCastle()`). First-run setup is the same in each
(shared `core-onboarding`): a key is generated on the phone and its public key
pushed to the host via a one-time password — two-phase, so the host-key
fingerprint is shown for confirmation *before* the password is sent. Each app
keeps its own key and onboards independently.

---

## Repository layout

A single multi-module Gradle build:

```
core-data/       SettingsStore (DataStore), KeyVault (AES-256-GCM at rest),
                 ServerProfile, ConfigState
core-ssh/        app-agnostic SSH primitives (sshj): connectWithKey + runCommand
                 + streamCommand (SshSession), SshKeygen, SshSecurity +
                 TofuHostKeyVerifier, BcOpenSshKeyProvider, shell/pathQuote,
                 hostKeyFingerprint, readCapped, LogLine + stream batching,
                 screen-session parsing, SshOnboarding (two-phase)
core-ui/         AppTheme (Material You), UiText
core-onboarding/ OnboardingController — shared two-phase key-onboarding flow
core-testing/    MainDispatcherRule, TESTING_CONVENTIONS (test-only deps)

app-lobber/     ┐
app-caster/     ├─ one Application + NavHost + ViewModels + app-specific
app-prodder/    ├  ssh/ (SshConfig, SshClient interface, SshjClient) per app
app-culler/     ┘
```

The app-shaped SSH bits (`SshConfig`, the `SshClient` interface, `SshjClient`,
`resolveConfig()`) deliberately live **per app**, not in core — the apps differ
(Prodder has no working dir; each runs different remote commands — install,
`screen`, `hardcopy`, `rm`). `core-ssh` holds only what is genuinely
app-agnostic.

---

## Build & install

```bash
./gradlew bundleRelease     # the deliverable: one unsigned release AAB per app
./gradlew testDebugUnitTest # all unit tests (pure JVM, no instrumentation)
```

The Gradle wrapper (9.5.1) is bundled — no local Gradle install needed. The
AABs land in `app-<name>/build/outputs/bundle/release/`.

Installation happens **host-side** via Lobber's `host-scripts/install-aab.sh`,
which converts AAB→APK with bundletool, signs it with the shared family key, and
pushes it to the device (USB or Tailscale/LAN endpoint):

```bash
~/apk/install-aab.sh app-lobber/build/outputs/bundle/release/app-lobber-release.aab
# repeat for app-caster / app-prodder / app-culler
```

Add `uninstall` as a second argument to wipe a prior install (data + Keystore)
before installing fresh.

---

## Architecture

Per app: `MainActivity` hosts a Compose `NavHost`; a `…ViewModelFactory` injects
the shared `SettingsStore` and a `createClient: (SshConfig) -> SshClient`
factory; each ViewModel exposes a `StateFlow` UI state that the `Screens`
render. No DI framework — manual DI through the `Application` class.

`SshClient` is always an interface so tests inject a MockK client; the real
`SshjClient` opens, authenticates, runs one command, and tears the connection
down.

---

## Security

- **Trust-on-first-use host-key pinning.** `TofuHostKeyVerifier` learns the
  host's `SHA256:…` fingerprint on first connect and pins it per
  `ServerProfile`; every later connect must match (constant-time compare) or the
  connection is aborted — no prompt, no silent accept. The password-based
  onboarding flow shows the learned fingerprint for explicit confirmation
  *before* the password is sent; residual risk is the first-contact window of a
  *pubkey* connect (no secret is exposed there), inherent to TOFU without an
  out-of-band fingerprint.
- **Private key encrypted at rest.** The Ed25519 key lives in
  `filesDir/id_ed25519` as Base64 of an AES-256-GCM ciphertext from `KeyVault`
  (non-exportable Android-Keystore key, hardware-backed where available), mode
  `0600`. Even a rooted dump is worthless without the device's Keystore key.
- **Per-app sandboxes.** Despite sharing code (and the same alias/DataStore-name
  *strings*), each app has its own sandbox: its own key, config and UID-bound
  Keystore key. The apps do **not** share data on-device — each onboards
  independently.
- **Trusted-LAN / tailnet scope.** Intended for a build host you control,
  reached on the LAN or over a WireGuard (Tailscale) overlay — not the open
  internet.

---

## Tests

Pure-JVM JUnit 4 + MockK + Turbine; Robolectric only where the Android runtime
is genuinely needed. `MainDispatcherRule` installs an `UnconfinedTestDispatcher`
as `Dispatchers.Main` (coroutines run eagerly); always use
`runTest(rule.dispatcher)`. Full reference:
`core-testing/.../TESTING_CONVENTIONS.kt`.

---

## Versioning

Each app versions independently; `versionName` matches its GitHub release tag.

| App | Version |
|---|---|
| Lobber | 0.7.2 |
| Caster | 0.6.2 |
| Prodder | 0.3.2 |
| Culler | 0.1.0 |

---

## Conventions & docs

- `CLAUDE.md` — project conventions and hard architectural rules.
- `MATERIAL3RULES.md` — Material 3 conformance (Part A generic, Part B sshTools).
- `WHY_CLAUDE.md` — the working agreement.

---

## Intentionally left out

- A generic SSH client / terminal emulator — these are single-purpose remotes.
- Cross-app key sharing — possible only via a single-app architecture or a
  signature-guarded `ContentProvider`; deliberately not done. Onboard each app
  once.
- A connection pool or persistent session — per-operation connections are cheap
  on a trusted LAN and avoid stale-socket failure modes.
- A DI framework — manual DI through the `Application` class is enough.
