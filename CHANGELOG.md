# Changelog

All notable changes to SonoraKMP. Generated from Conventional Commits.

## Unreleased

### Features

- **ui:** Add gestures, optimistic controls and M3 seek bar
- **ui:** Add song sheets, playlist dialogs and playlist page
- **ui:** Add album, artist and playlist tabs and details
- **update:** Add nightly update channel
- **ui:** Add Songs tab, sync banner and plays sort
- **ui:** Add Now Playing with the Spine
- **ui:** Add home header, search and sort
- **ui:** Add onboarding, mini player, seek bar and Up Next
- Rename app to SonoraKMP
- **playback:** Add playback service and UI connection
- **playback:** Add player with fades and queue window
- **widget:** Add now playing home-screen widget
- **playback:** Add stats recorder and position ticker
- **playback:** Add equalizer, focus and headphone handling
- **playback:** Add sleep timer and session restore
- **app:** Wire repositories into DI
- **shared:** Add backup export and restore
- **shared:** Add library sync with relink by path
- **scan:** Add combined library scanner
- **shared:** Add playlist, favorites and stats repos
- **scan:** Add folder scanner and SAF sidecar index
- **app:** Add share, URL and folder helpers
- **scan:** Add MediaStore scanner and tag reader
- **scan:** Add artwork cache and seed colours
- **ui:** Switch to flat Sleeve design system
- **site:** Redesign with flat sleeve identity
- **shared:** Add backup codec and restore planner
- **shared:** Add ReplayGain, effects and sleep timer logic
- **shared:** Add library grouping, sort and search
- **shared:** Add lyrics, M3U and stats logic
- **shared:** Add update checker
- **shared:** Add queue logic
- **app:** Add DI and navigation shell
- **shared:** Add settings store and repo contracts
- **ui:** Add core components
- **shared:** Add Room database schema
- **app:** Add manifest, components and playback contract
- **ui:** Add locale-aware format helpers
- **ui:** Add Afterglow theme
- **l10n:** Add English and Japanese strings
- **shared:** Add domain models
- **site:** Add landing and download page

### Bug fixes

- **ui:** Wire Up Next, fix seek bar, Opus covers and permissions
- Skip broken files and adopt quirk fixes on Android
- **shared:** Fix Flutter grouping, search and queue quirks
- **playback:** Keep custom EQ when toggling presets
- **stats:** Arm listen timer when speed is zero

### Performance

- **scan:** Return songs first, enrich in background

### Refactoring

- Drop Android Auto support

### Build

- Add markdown, licenses and test deps
- Derive versionCode from versionName
- Add MaterialKolor and reorderable
- Switch to Gradle KMP scaffold

### CI

- Prune Actions caches after CI and daily
- Run shared tests with testDebugUnitTest
- Drop version heading from nightly notes
- Release on release: commits, add nightly builds
- Bump actions to latest majors
- Deploy site to GitHub Pages
- Add tagged release workflow
- Add git-cliff release notes config
- Add build and test workflow

