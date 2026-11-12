# SonoraKMP — handoff

Everything still to do, so you can continue on your own. Written 2026-11-12.

## Plans and references

| Document | What it is |
| --- | --- |
| [TASKS.md](TASKS.md) | Full task list with IDs, owners and "done when"; progress log at the top of P5 |
| [SPEC.md](SPEC.md) | Features with acceptance criteria, data models, navigation, decisions D-01…D-68 |
| [DESIGN.md](DESIGN.md) | "Sleeve" design system: palette, type, shapes, motion, the Spine, Home §6b |
| [FINAL_REPORT.md](FINAL_REPORT.md) | Flutter-vs-Kotlin parity table, known gaps, maintainer steps |
| [audit/settings_wiring.md](audit/settings_wiring.md) | Every setting traced to the code that reads it (✅/🕑/❌/⚠️) |
| [audit/data.md](audit/data.md), [audit/playback.md](audit/playback.md), [audit/ui.md](audit/ui.md), [audit/settings.md](audit/settings.md) | Behaviour of the Flutter app, section by section |
| [audit/strings.tsv](audit/strings.tsv) | All Flutter strings, en + ja |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | Commit convention, checks, releases, strings, CI caching |
| [../README.md](../README.md) | Install, build, release, secrets, Pages |
| [../site/README.md](../site/README.md) | Website: screenshots, fonts, Japanese copy |

## Where the code is

- **`main`**: green in CI, published as the nightly. Includes the former `wip/p11` (P5-28…P5-33).
- **`wip/p11`**: P5-28…P5-33 finished, lint + unit tests + `assembleDebug` green, player features checked on
  the emulator (API 37). Not merged into `main` yet.

  ```bash
  git switch wip/p11
  ```

## To do

### 1–2. Done on `wip/p11` (2026-11-12)

- P5-28 styles: vinyl spins while playing and freezes on pause; minimal has the controls full width under the
  Spine (labels were cut off) and a compact scrolling form below 560 dp height (landscape pane clipped the
  transport). P5-29 blur and P5-30 visualizer checked on screen.
- P5-31: the branch's back-handler fix was right; `BackOrderTest` failed only because it pressed back before
  the player's handler was registered. Settings + player → back collapses the player on the emulator too.
- P5-32: duration + finish song, finish-only, +1 min while finishing; the sheet preselects the default minutes.
- §2 settings: colour source Album art / Custom and `defaultThemeColor`, title filters (UI via
  `LocalDisplayPrefs`, notification/Auto/widget via `QueueController`), local artist images, favourites sort and
  `songActivityView` are wired; see [audit/settings_wiring.md](audit/settings_wiring.md).
- Lint: predictive-back corners use the public navigationevent transition state, not `Transition.isSeeking`.
- Not checked: vinyl in landscape, tablet sizes, the sleep timer's fade by ear.

### 3. Before 1.0

`wip/p11` is merged into `main` (2026-11-12). 1.0.0 is **not** close: keep shipping nightlies and only cut
`release: 1.0.0` when the maintainer decides the app is ready. Open items:

1. Optional: vinyl in landscape and on a tablet.
2. Run a nightly after big merges (*Actions → Nightly → Run workflow*, tick `force`).
3. The device checks in §4.

### 4. Still untested on a device

Widget on a home screen · Bluetooth headset buttons · danger-zone wipe · display cutouts · equalizer, fades
and speed by ear.

### 5. Maintainer decisions and one-time steps

- Back up `~/.sonora-signing/` (keystore + passwords).
- Google Play (if used): register certificate SHA-256
  `3A:9E:EC:49:40:6E:C7:85:A9:61:B3:01:97:40:30:B8:98:00:BD:D7:B1:FF:0B:59:5B:BF:7C:7C:49:38:4B:F1`.
- Q-2: keep SonoraKMP side by side with Flutter Sonora, or switch IDs/keys.
- Optional: branch protection requiring CI on `main`; a native speaker's look at the Japanese.
- Later: move Material 3 from `1.5.0-rc01` to the stable release when it ships.

## How to work in this repo

**Checks before every push to `main`** (CI runs exactly these):

```bash
bash scripts/check_product_name.sh
```

```bash
bash scripts/check_hardcoded_strings.sh
```

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```

Definition of done for a release build: `./gradlew testDebugUnitTest assembleRelease`.

**Rules that keep CI green**

- No user-visible text in code: add it to `res/values/strings_<feature>.xml` and `res/values-ja/…`
  (`StringsParityTest` fails if en and ja differ). The product name is "SonoraKMP".
- In composables read strings with `LocalResources.current`, not `LocalContext.current` (lint error), and
  locale with `currentLocale()`, not `Locale.getDefault()`.
- English strings are sentence case; generated ones are overridden in `scripts/strings/en_overrides.tsv`
  (`python3 -I scripts/strings/gen_strings.py`).
- Database changes need a new Room version, a migration in `shared/.../db/Migrations.kt` and the
  `MigrationTest` (D-68).
- Tests stay lean (~30): add one only when it guards real behaviour or a fixed bug.

**Local builds:** the Mac has 16 GB; run one Gradle build at a time with the emulator open. If a build hangs,
`./gradlew --stop`, and check no source file has exploded in size (`git diff --stat`).

**Debug helpers (debug builds only):** `DebugLibraryProbe` lets adb trigger a sync or playback:

```bash
adb shell am broadcast -n de.yurtemre.sonorakmp.dev/de.yurtemre.sonora.DebugLibraryProbe -a de.yurtemre.sonora.DEBUG_SYNC --es folder /storage/emulated/0/Music
```

Actions: `DEBUG_SYNC`, `DEBUG_PLAY`, `DEBUG_STATE`; output in `adb logcat -s SonoraProbe`.

**Releases:** `release: X.Y.Z` commit on `main` → tag, signed APK + `.sha256` + AAB, git-cliff notes.
Nightlies build daily from `main` and are offered in-app only on the Nightly channel.
