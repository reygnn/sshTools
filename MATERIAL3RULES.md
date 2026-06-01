# MATERIAL3RULES.md

Binding rules for Material 3 conformance.

This file has two parts:

- **Part A — General**: applies to *every* Compose-M3 app. Copy 1:1 into
  any project.
- **Part B — sshTools-specific**: extends and concretises Part A
  for this repo. When porting to another project, replace this part — the
  semantic role assignments, named colour constants, and documented hex
  exceptions are project-dependent.

This file supplements `CLAUDE.md` — it does not replace it.

---

# Part A — General (any Compose-M3 app)

## A1. Theme layers

There are **two layers** that act in this order:

1. **XML app theme** (`res/values/themes.xml`, `res/values-night/themes.xml`)
   — purely functional: only covers the pre-Compose window (splash, window
   background, edge-to-edge) and renders **no** UI in a pure Compose app.
   A lean platform theme as parent is therefore enough:
   `@android:style/Theme.DeviceDefault.Light.NoActionBar` in `values/`,
   `@android:style/Theme.DeviceDefault.NoActionBar` in `values-night/`.
   No `com.google.android.material`/`appcompat` needed. Never
   `android:Theme.Material.*` (Android-5-Material, obsolete — DeviceDefault
   is the modern OEM default). Carries the platform splash attributes
   (see A2). Only when real Material views / XML widgets enter the picture
   does `Theme.Material3.*` + `com.google.android.material` become necessary.
2. **Compose theme** (`MaterialTheme { ... }`) — fully populated
   `ColorScheme`. Compose paints over everything after `setContent {}` anyway,
   so the XML theme can stay purely functional (splash + edge-to-edge
   background).

## A2. Splash screen

- With `minSdk ≥ 31` (Android 12) the **platform** delivers the splash
  natively. No `androidx.core:core-splashscreen` — the library is only a
  backport for API < 31.
- Configuration exclusively via theme attributes (with the `android:`
  prefix) directly on the app theme: `android:windowSplashScreenBackground`,
  `android:windowSplashScreenAnimatedIcon`,
  `android:windowSplashScreenIconBackgroundColor`. No separate splash
  theme, no `installSplashScreen()`, no `postSplashScreenTheme`.
- The system splash fades automatically on the first Compose frame — no
  code in `onCreate` needed. Only if you want to deliberately hold it or
  customise the exit animation: platform `getSplashScreen()` (API 31+).
- The splash icon is its own vector drawable, **never**
  `@android:drawable/*`. Adaptive-icon foreground and splash icon can use
  the same drawable.
- Splash background colours in `res/values/colors.xml`, centralised,
  separate for light and dark, no hex directly in the theme.

## A3. Launcher icon

- Adaptive icon is mandatory: `mipmap-anydpi-v26/ic_launcher.xml` +
  `ic_launcher_round.xml`, each with `<background>`, `<foreground>`,
  **and** `<monochrome>` (themed icons on Android 13+).
- Foreground vector uses the 108×108 viewport convention and respects the
  safe zone (~66 dp centred).
- The manifest sets `android:icon` **and** `android:roundIcon`.
- With `minSdk ≥ 26`, `mipmap-*dpi` PNGs are redundant — adaptive icon
  everywhere.

## A4. ColorScheme — fully populate

`darkColorScheme()` / `lightColorScheme()` have defaults for unset roles
that land in purple tones and wreck the branding (typically visible on
OutlinedTextField borders, SegmentedButton, dividers, elevated cards).
**Every** one of these roles must be set in both schemes:

- Primary family: `primary`, `onPrimary`, `primaryContainer`,
  `onPrimaryContainer`, `inversePrimary`
- Secondary family: `secondary`, `onSecondary`, `secondaryContainer`,
  `onSecondaryContainer`
- Tertiary family: `tertiary`, `onTertiary`, `tertiaryContainer`,
  `onTertiaryContainer`
- Backgrounds: `background`, `onBackground`
- Surfaces: `surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`,
  `surfaceTint`, `surfaceBright`, `surfaceDim`
- Surface container tiers: `surfaceContainerLowest`, `surfaceContainerLow`,
  `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`
- Inverse: `inverseSurface`, `inverseOnSurface`
- Structure lines: `outline`, `outlineVariant`, `scrim`
- Error family: `error`, `onError`, `errorContainer`, `onErrorContainer`

## A5. Hardcoded colours — rule

- `Color(0x…)` is allowed **exclusively** in the theme file
  (`ui/theme/Theme.kt` or equivalent).
- In screens, only `MaterialTheme.colorScheme.*` or named constants from
  the theme file.
- Exceptions must be **deliberately documented**, not silently present
  (see Part B).

Pre-commit check (adjust path as needed):
```bash
git grep -nE 'Color\(0x[0-9A-Fa-f]+\)' app/src/main/java/**/ui/screens
```
Expected: no matches.

## A6. Dark/light correctness

Three common traps:

1. **Using `Color.White` / `Color.Black` directly** — breaks in the other
   mode. Use `onSurface`, `inverseSurface`, or a themed colour instead.
2. **Alpha values on a light background** — `.copy(alpha = 0.x)` on a hex
   value without theme reference can become invisible in light mode.
   Prefer `onSurfaceVariant` as a base and use alpha sparingly.
3. **Canvas drawings** — `Color` must be read from `MaterialTheme`
   **outside** the canvas lambda (the canvas lambda is not a composable
   scope). Pattern:
   ```kotlin
   val lineColor = MaterialTheme.colorScheme.primary
   Canvas(...) { drawPath(path, lineColor, ...) }
   ```

## A7. Semantic roles — a choice

Material 3 has no `warning` / `success` / `info` role. Two ways to solve
this — decide **once** per app and document it (in Part B):

1. **Repurpose M3 roles**: e.g. `tertiary` = success, `secondary` =
   accent. Works when `primary` already carries the main action colour
   and the other roles are free.
2. **Named theme constants** for states that sit outside the M3 role set
   (e.g. amber for "in progress", blue for "mode B"). Must be centralised
   in the theme file, not in screens.

For a third semantic accent: **first** check whether an M3 role is
unused, **then** add it as a constant in the theme file.

## A8. Dependencies

These deps belong to the M3 baseline:

- `androidx.compose.material3:material3` (via Compose BOM)
- `androidx.compose.material:material-icons-extended` (optional, if
  Material icons are used)

Not needed in a pure Compose app:

- `com.google.android.material:material` — only required for a
  `Theme.Material3.*` XML parent; the lean platform theme (A1) does
  without it.
- `androidx.core:core-splashscreen` — with `minSdk ≥ 31`, the platform
  handles the splash (A2).

---

# Part B — sshTools-specific

sshTools bundles three apps (`app-lobber`, `app-caster`, `app-prodder`) that
share **one** Compose theme: `core-ui/AppTheme.kt`. The rules below apply to
all three identically.

## B1. Semantic role assignments

`AppTheme` uses **full Material You dynamic colour** (`dynamicLightColorScheme`
/ `dynamicDarkColorScheme`) — every role is wallpaper-derived, there is **no**
static brand scheme and **no** named semantic constants. These are utilities
for a trusted build LAN, not branded products; the system palette is the
intended look.

If a future screen needs a `success` / `warning` accent (A7), prefer an unused
M3 role first; only add a named constant to `core-ui` (never to an app's
screens) if no role fits.

## B2. Documented hex exceptions

None. There is no `Color(0x…)` outside the theme layer. Screens read
`MaterialTheme.colorScheme.*` exclusively.

## B3. Dynamic colour policy

`dynamicColor` is effectively always on (see B1): **all** roles come from the
wallpaper, nothing is locked to a static brand. The apps target Android 16, so
`dynamicLight/DarkColorScheme` are always available — no static fallback.

## B4. Concrete theme files

- Compose theme: `core-ui/src/main/java/com/github/reygnn/core/ui/AppTheme.kt`
  (shared by all three apps).
- XML window themes: `app-<name>/src/main/res/values/themes.xml`, each a lean
  `@android:style/Theme.DeviceDefault.Light.NoActionBar` carrying only the
  Material You splash background (`@android:color/system_neutral1_50`). No
  `android:Theme.Material.*` (A1).

---

## What this file is NOT

- Not a Material 3 tutorial — see [m3.material.io](https://m3.material.io/)
  and the Compose docs.
- Not an exhaustive style spec — typography, shape, motion are
  deliberately omitted (defaults for now).
- Not history notes for past refactorings — those live in commits.
