# Sonora

[![CI](https://github.com/yurtemre7/SonoraKMP/actions/workflows/ci.yml/badge.svg)](https://github.com/yurtemre7/SonoraKMP/actions/workflows/ci.yml)
[![Release](https://github.com/yurtemre7/SonoraKMP/actions/workflows/release.yml/badge.svg)](https://github.com/yurtemre7/SonoraKMP/actions/workflows/release.yml)

A private music player for the music files you own. No account, no analytics,
no uploads. Sonora reads your folders, plays them well, and keeps your
playlists, favourites and listening stats on your phone.

This is the native Kotlin rewrite of the [Flutter version of Sonora](https://github.com/yurtemre7/sonora),
built with Jetpack Compose and Material 3 Expressive, Android only.
Website: <https://yurtemre7.github.io/SonoraKMP/>

> **Status:** in active development. The foundation, core logic, release
> pipeline and website are done; library scanning, playback and the screens are
> being built. Progress is tracked in [docs/TASKS.md](docs/TASKS.md).

## Features

**Library**
- Plays the music on your device (MediaStore), or a folder you pick
- Songs, albums, artists and playlists, with search and per-tab sorting
- Playlists with drag-to-reorder and M3U import/export
- Favourites, recently played, and a listening statistics dashboard
- Synced `.lrc` and plain `.txt` lyrics

**Playback**
- Background playback with notification, lock-screen and headset controls
- Gapless playback, ReplayGain (track or album), smooth play/pause fades
- Equalizer with presets, speed and pitch presets from slowed to nightcore
- Sleep timer with "finish this song" and a fade-out
- Home-screen widget

**Yours**
- Backup and restore to a JSON file (compatible with Flutter Sonora backups)
- Light, dark and pitch-black themes; Material You, album-art or custom colours
- English and Japanese, with a per-app language setting
- Updates itself from GitHub releases, with checksum verification and an
  optional pre-release channel

## Install

Download `sonora-<version>.apk` from the [latest release](https://github.com/yurtemre7/SonoraKMP/releases/latest)
and open it. Android asks once to allow installs from your browser or file
manager. Requires Android 7.0 or newer.

**Coming from Flutter Sonora?** The two apps install side by side (different
app ID and signing key). Export a backup in the Flutter app
(*Settings → Backup*), then restore it in this one. Playlists, favourites,
stats and settings carry over; songs are matched against your library.

## Build

Requirements: JDK 17+, Android SDK with platform 37.

```bash
./gradlew assembleDebug                  # debug APK (app ID suffix .dev, installs next to release)
./gradlew :shared:testAndroidHostTest    # shared logic tests
./gradlew testDebugUnitTest              # app unit and Robolectric tests
./gradlew lintDebug                      # Android lint
./gradlew testDebugUnitTest assembleRelease
```

Run it on a device or emulator:

```bash
./gradlew :androidApp:installDebug
adb shell am start -n de.yurtemre.sonorakmp.dev/de.yurtemre.sonora.MainActivity
```

Release builds are signed with `keystore.properties` in the project root if it
exists (git-ignored), otherwise with the debug key:

```properties
storeFile=/absolute/path/to/sonora-release.jks
storePassword=...
keyAlias=sonora
keyPassword=...
```

### Project layout

| Path | What |
| --- | --- |
| `shared/` | Kotlin Multiplatform module (Android target): models, library logic, queue, ReplayGain, lyrics, M3U, stats, backup, update checker, Room database, settings |
| `androidApp/` | Compose UI, Media3 playback service, widget, updater, resources (`values/` English, `values-ja/` Japanese) |
| `docs/` | [SPEC](docs/SPEC.md), [TASKS](docs/TASKS.md), [DESIGN](docs/DESIGN.md), and the audit of the Flutter app |
| `site/` | Static landing page, deployed to GitHub Pages |
| `scripts/strings/` | Generator for the string resources |

Stack: Kotlin 2.4, Jetpack Compose, Material 3 (1.5 expressive APIs),
Navigation 3, Media3, Room, DataStore, Koin, Ktor, Coil.

## Release

Versions are never bumped by hand. A release is a commit on `main` whose
subject is `release: <version>`:

```bash
git commit --allow-empty -m "release: 1.0.0"
git push origin main
```

The [release workflow](.github/workflows/release.yml) then tags `v1.0.0` and
builds, signs and publishes `sonora-<version>.apk`, its `.sha256` checksum and
an `.aab`, with release notes generated from the commit history by
[git-cliff](cliff.toml) (grouped by type, with contributor credits and a link
to the full diff). Versions with a hyphen (`release: 1.1.0-beta.1`) become
pre-releases. The version code is derived from the version name.

**Nightly builds.** Every night, if `main` changed, the
[nightly workflow](.github/workflows/nightly.yml) replaces the
[nightly pre-release](https://github.com/yurtemre7/SonoraKMP/releases/tag/nightly)
with a fresh signed APK. Nightlies install over a release but are never
offered by the in-app updater.

To check a release without publishing it, run the workflow manually
(*Actions → Release → Run workflow*) with **dry run** checked. Artifacts and
notes are attached to the run instead.

## One-time setup (maintainers)

**Signing secrets.** Add these under *Settings → Secrets and variables → Actions*:

| Secret | Value |
| --- | --- |
| `SONORA_KEYSTORE_BASE64` | the release keystore, base64-encoded (`base64 -i sonora-release.jks`) |
| `SONORA_KEYSTORE_PASSWORD` | keystore password |
| `SONORA_KEY_ALIAS` | key alias |
| `SONORA_KEY_PASSWORD` | key password |

Create a keystore with
`keytool -genkeypair -keystore sonora-release.jks -alias sonora -keyalg RSA -keysize 4096 -validity 10000`
and back it up: losing it means existing installs can't be updated.

**GitHub Pages.** *Settings → Pages → Source: GitHub Actions*. The
[Pages workflow](.github/workflows/pages.yml) deploys `site/` on every change.

## Contributing

Commits follow [Conventional Commits](https://www.conventionalcommits.org/)
(`feat:`, `fix:`, `docs:` …); the release notes are built from them. All
user-visible text lives in string resources, in English and Japanese. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the details.

## Privacy

Sonora never uploads your music or data. The only network requests check
GitHub for new releases and download an update when you ask for it.

## License

[MIT](LICENSE). Fonts: Bricolage Grotesque, SIL Open Font License
([docs/licenses](docs/licenses)).
