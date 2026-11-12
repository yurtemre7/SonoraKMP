# SonoraKMP — final port report

Date: 2026-11-12 · Source: Flutter Sonora 1.28.1 (`kind-salk`) · Target: SonoraKMP (Kotlin, Jetpack Compose,
Material 3 Expressive, Android only)

## Summary

The native port is feature-complete for daily use and builds, tests and releases through CI. Behaviour follows
the Flutter app; the look is new ("Sleeve", see [DESIGN.md](DESIGN.md)). Flutter bugs were fixed rather than
copied (decisions D-56–D-64 in [SPEC.md](SPEC.md)). Three cosmetic settings from the Flutter app are present in
Settings but not implemented yet (see Known gaps).

| Definition of done | Status |
| --- | --- |
| Every SPEC feature works on a real or emulated device | Mostly; exceptions under Known gaps |
| `./gradlew testDebugUnitTest assembleRelease` passes | ✅ clean build, 29 tests, 0 failures, release APK signed with the release key |
| Update flow verified against a test release | ✅ via the Nightly channel against the public nightly (older release-signed build updated itself on the emulator); no tagged pre-release was published |
| Release and Pages workflows validated | ✅ release dry run, nightly builds, Pages deploy; a real `release:` commit has not been pushed yet |
| README covers build, release and contribution | ✅ |

Numbers: 160 planned tasks done, 29 tests, 68 recorded decisions, ~110 commits.

## Parity table

✅ ported · ✅+ ported and improved · ⚠️ partial · ❌ not ported

| Area | Flutter feature | SonoraKMP | Notes |
| --- | --- | --- | --- |
| Library | MediaStore scan, folder fallback | ✅+ | Songs appear after the MediaStore query; covers, lyrics, ReplayGain, exact MP3 lengths and colours fill in the background with visible progress. Scan speed confirmed on the maintainer's phone |
| Library | Incremental sync, cached library | ✅ | |
| Library | Album art extraction | ✅+ | Ogg/Opus embedded covers added |
| Library | Lyrics sidecars (.lrc/.txt) | ✅+ | Read through the picked folder on Android 11+ (D-55) |
| Library | Artist images | ✅ | |
| Library | Song durations | ✅+ | VBR MP3s without a Xing header measured by frame counting (were off by minutes) |
| Library | Grouping, per-tab sort, search | ✅+ | Album artist, disc/track split, `AC/DC` kept whole, accent/width/kana-folding search |
| Library | Playlists, M3U import/export, reorder | ✅ | |
| Library | Favourites, recently played | ✅ | |
| Library | ID changes break playlists/stats | ✅+ | Playlists, stats and favourites re-link by file path (D-49) |
| Library | Sync reminder banner | ✅ | |
| Playback | Background service, notification, lock screen | ✅ | Media3 |
| Playback | Gapless, play/pause fades, ReplayGain | ✅+ | Exact volume restored after fades (D-58) |
| Playback | Queue, shuffle, repeat, play next | ✅+ | FIFO play next, repeat remembered, broken files skipped |
| Playback | Equalizer, speed/pitch presets, Bluetooth patch | ✅ | Custom EQ no longer switched off by presets |
| Playback | Sleep timer + notification actions | ✅ | |
| Playback | Session restore, keep playing on close | ✅ | |
| Playback | Audio focus, pause on disconnect, resume on reconnect | ✅+ | Pause-on-disconnect setting now actually honoured |
| Playback | Seek bar total | ✅+ | Same time base at any speed; never below position |
| Playback | Android Auto | ❌ | Dropped at the maintainer's request (D-52) |
| Playback | Home-screen widget | ✅ | Not yet placed on a home screen for testing |
| UI | Onboarding, permissions, folder pick | ✅+ | Name optional, no forced "User" greeting |
| UI | Home tabs, mini player | ✅+ | Fixed header across tabs, swipe rows, drag-up mini player, optimistic controls |
| UI | Now Playing: lyrics, up next, related | ✅+ | Spine title, wavy seek bar, swipe cover to skip, tablet pane |
| UI | Now Playing styles (modern / vinyl / minimal) | ⚠️ | Setting exists; every style renders the modern layout |
| UI | Blurred backgrounds | ⚠️ | Setting exists; no blur is drawn (flat tint only) |
| UI | Audio visualizer | ⚠️ | Setting exists; no visualizer is drawn |
| UI | Album, artist, playlist pages, favourites, stats | ✅ | Redesigned |
| UI | Tablet / landscape layouts | ✅+ | New: list-detail, Now Playing pane, rails |
| Settings | All settings screens, language picker | ✅ | |
| Settings | Backup / restore | ✅ | Reads and writes Flutter backup v1 (migration path) |
| Settings | Danger zone | ✅+ | Wipes everything it promises (Flutter left files behind); not run on a device |
| Settings | Changelog, licences | ✅+ | Bundled, works offline |
| Updates | In-app updater | ✅+ | Universal APK + SHA-256 check, PackageInstaller, channels Stable / Pre-release / Nightly |
| Languages | English, Japanese | ✅+ | Parity test, reviewed Japanese, per-app language, no hardcoded text (CI check) |
| Platforms | iOS, Windows | ❌ | Out of scope (Android only) |

## Known gaps

1. **Three settings do nothing yet:** Now Playing style (vinyl/minimal), blurred backgrounds, visualizer.
   Either implement them in the Sleeve style or remove the switches. Tracked as P5-28, P5-29, P5-30.
2. **Not tested on a device:** home-screen widget placement, Bluetooth headset buttons, danger-zone wipe,
   sleep-timer finish-song mode, display cutouts (checked in code only), equalizer/fades/speed by ear.
3. **No stable release yet:** the website and the Stable update channel fall back to the nightly until
   `release: 1.0.0` is pushed.
4. **Side by side with Flutter Sonora:** different app ID and signing key, so no in-place upgrade; users move
   over by restoring a Flutter backup (open question Q-2).
5. **Material 3 1.5.0-rc01:** a release candidate (needed for the expressive components); move to the stable
   release when it ships.
6. Minor: predictive-back corner radius on screen transitions not added; one leftover 404 seen once in the
   site console during QA, not re-checked.

## Manual steps for the maintainer

Already done: GitHub signing secrets set, GitHub Pages enabled (source: GitHub Actions).

1. **Back up the signing key** in `~/.sonora-signing/` (keystore + `keystore.properties`). Losing it means no
   more updates over existing installs.
2. **Cut the first release:**

   ```bash
   git commit --allow-empty -m "release: 1.0.0"
   git push origin main
   ```

3. **Google Play (if used):** register the certificate SHA-256
   `3A:9E:EC:49:40:6E:C7:85:A9:61:B3:01:97:40:30:B8:98:00:BD:D7:B1:FF:0B:59:5B:BF:7C:7C:49:38:4B:F1`
   (upload key if Play App Signing is on).
4. **Decide Q-2:** keep SonoraKMP side by side with Flutter Sonora, or plan a switch to the Flutter app's
   ID and key.
5. **Optional:** require the CI check on `main` (branch protection); have a native speaker skim the Japanese.
6. **Quick device checks:** add the widget to a home screen, try Bluetooth buttons, set a sleep timer with
   "finish this song".
