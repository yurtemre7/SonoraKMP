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

- **`main`**: green in CI, published as the nightly. Everything up to the final report.
- **`wip/p11`**: unfinished work, rebased on `main`. It **compiles**; nothing in it has been run through
  lint, the full tests or the emulator yet.

  ```bash
  git switch wip/p11
  ```

## To do

### 1. Finish `wip/p11`

| Task | State on the branch | Still to do |
| --- | --- | --- |
| **P5-28** Now Playing styles | `VinylArtwork.kt`, `MinimalPlayer.kt`, `PlayerView.kt` switch | Check vinyl (record spins while playing, frozen with reduce motion) and minimal (Spine full height, big time, controls) on screen; gestures still work; landscape/tablet fallbacks; delete `TODO(P5-07)` in `NowPlayingOverlay.kt` |
| **P5-29** Blurred backgrounds | `TintedBackdrop.kt` | Verify blur on API 31+ under a flat ~0.88 surface overlay; flat tint below 31 and when off |
| **P5-30** Visualizer | `ui/components/Visualizer.kt` | Place it above the controls when the setting is on; animate only while playing; freeze with reduce motion |
| **P5-31** Back-press order | `ui/navigation/{NowPlayingSlot,SonoraAppShell,Transitions}.kt`, `test/.../ui/BackOrderTest.kt` | Make the expanded player's back handler win over the screen below it (bug: Settings + player → back popped Settings). Then sheets/dialogs → player → search/selection → screen. Run `BackOrderTest`, reproduce on a device |
| **P5-32** Sleep timer: duration + finish song | `SleepTimerState.kt` reducer, `playback/sleep/*`, `SleepTimerSheet.kt`, `strings_sleep2.xml`, `SleepTimerStateTest.kt` | Duration runs out → the current track plays to its end (fade over its last seconds) → stop. Finish-only stops after this song. +1 min while finishing returns to counting. Sheet: independent switch; preselect `sleepTimerDefaultMinutes`. Run the test |
| **P5-33** Settings audit | ✅ done: `audit/settings_wiring.md`, `SettingsTest` | Fix the findings in §2 |

### 2. Settings that do nothing yet (from the audit)

| Setting | Fix in |
| --- | --- |
| Colour source Album art / Custom (⚠️) and `defaultThemeColor` (❌) | `MainActivity.kt`: pass `seedColor` to `SonoraTheme`: current song's seed colour for Album art, `defaultThemeColor` for Custom |
| `preferLocalArtistImages` | `ArtistAvatar` (or `ArtistsTab.kt:110`, `ArtistRoute.kt:154`, `FavoritesRoute.kt:382`, `StatsRoute.kt:206`) |
| `filterTitleFeatures`, `filterTitleArtist` | `playback/QueueController.kt:46` (titles are `{ it.title }`) and title display in `SongRow`, `MiniPlayer`, Now Playing: use shared `TitleFormatter` |
| `sleepTimerDefaultMinutes` | `SleepTimerSheet.kt` (part of P5-32) |
| `favoritesSortBy`, `favoritesSortAscending` | `favorites/FavoritesViewModel.kt:41` (sort only kept in memory) |
| `songActivityView` | `home/HomeViewModel.kt:100` (derived from the plays sort direction instead) |

Afterwards, add a row per setting to the audit table and set it to ✅.

### 3. Merge and release

1. Run the CI command and the two checks (below) on `wip/p11`; fix lint errors.
2. Check on a device: player styles, blur, visualizer, back order, sleep timer.
3. Merge into `main` (squash is fine), push, confirm CI, then run a nightly
   (*Actions → Nightly → Run workflow*, tick `force`).
4. Tick P5-28…P5-33 in TASKS.md and update the gaps in FINAL_REPORT.md.
5. First real release:

   ```bash
   git commit --allow-empty -m "release: 1.0.0"
   git push origin main
   ```

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
