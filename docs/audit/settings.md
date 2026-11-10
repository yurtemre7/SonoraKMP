# Sonora audit: Settings, Theming, Localization, Updates, Release tooling

Repo: `/Users/yurtemre/Code/antigravity/kind-salk` (Flutter, version `1.28.1+1` in pubspec, package `de.yurtemre.sonora`).
Read-only audit for a native Kotlin/Android port. All strings quoted below are the English (`app_en.arb`) values; the full en/ja table is in `strings.tsv` next to this file.

Primary sources:
- `lib/providers/settings_provider.dart` (singleton `SettingsProvider.instance`, ChangeNotifier)
- `lib/providers/theme_provider.dart` (theme mode)
- `lib/providers/player_theme.dart` (part of PlayerProvider: album-art color, fallback/custom color)
- `lib/providers/player_persistence.dart` (loads `default_theme_color`, mfx keys)
- `lib/theme/app_theme.dart`, `lib/utils/color_extractor.dart`, `lib/widgets/theme_color_selector.dart`
- `lib/app.dart` (MaterialApp wiring, `_resetApp`)
- `lib/screens/settings_screen.dart`, `lib/screens/settings/*.dart`
- `lib/services/update_service.dart`, `lib/widgets/update_dialog.dart`, `lib/screens/changelog_screen.dart`
- `lib/services/backup_service.dart` (`_settingKeys` list of backed-up prefs)
- `android/app/src/main/kotlin/de/yurtemre/sonora/MainActivity.kt` (method channel handlers used by these features)

---

## 0. Storage model (important for port)

- All settings use `SharedPreferencesAsync()` (shared_preferences new async API). On Android this is backed by **Jetpack DataStore Preferences** (plugin default file name `FlutterSharedPreferences`; verify on device: `files/datastore/FlutterSharedPreferences.preferences_pb`). Keys are stored **without** the legacy `flutter.` prefix.
  - Port: if the Kotlin app keeps `applicationId de.yurtemre.sonora` and wants to keep user settings on upgrade, read that DataStore file with the same key names (or do a one-time migration).
  - Dart `int` is 64-bit; `setInt` is stored as **Long**. Colors (`default_theme_color`, `dynamic_theme_color`) are stored as unsigned ARGB (e.g. `0xFF7C4DFF` = 4286336511) which overflows Kotlin `Int` → read as `Long` and `.toInt()`.
- Settings are loaded once in `SettingsProvider.loadSettings()` (called at app start from `_loadSongsCore` and after a backup restore). Every setter: update field → `notifyListeners()` → persist (write-after-notify, fire-and-forget).
- Theme mode lives in a separate `ThemeProvider` (key `theme_mode`), loaded in its constructor; has a legacy migration from `<appDocs>/theme_prefs.json` (`{"themeMode": "..."}`) that is copied into prefs and the file deleted.
- Some settings belong to `PlayerProvider` rather than `SettingsProvider`: `default_theme_color` (fallback/custom accent), `mfx_bluetoothMfxPatch`.
- Some values are also read directly by other components from prefs: `keep_playing_on_close` is read inside `SonoraAudioHandler.onTaskRemoved()` straight from prefs (not via provider).

---

## 1. Master list of all SharedPreferences keys in this area

| Key | Type | Default | Allowed values | Owner | UI location | Backed up |
|---|---|---|---|---|---|---|
| `app_locale` | String | `'system'` | `system`, `en`, `ja` (code also maps `de` → Locale('de') but no de ARB exists; falls back to en) | SettingsProvider | Settings › App Language dialog | yes |
| `theme_mode` | String | `'system'` | `system`, `light`, `dark` | ThemeProvider | Appearance › Theme Mode | yes |
| `use_dynamic_theme` | bool | `true` | — (part of color source) | SettingsProvider | Appearance › Theme Colors | yes |
| `use_material_you` | bool | `false` | — (part of color source) | SettingsProvider | Appearance › Theme Colors | yes |
| `dynamic_theme_color` | int (ARGB) | unset → `0xFF7C4DFF` | any | SettingsProvider | none (dead key: loaded, but setter `setDynamicThemeColor` is never called; field is mutated in-memory by `_applyThemeColor`) | yes |
| `default_theme_color` | int (ARGB) | unset → `0xFF7C4DFF` | any color from the selector | PlayerProvider (`player_theme.dart`) | Appearance › Fallback / Custom color | yes |
| `amoled_dark` | bool | `false` | | SettingsProvider | Appearance › Display & Contrast | yes |
| `blurred_backgrounds` | bool | `false` | | SettingsProvider | Appearance › Display & Contrast | yes |
| `now_playing_style` | String | `'modern'` | `modern`, `vinyl`, `minimalist` (unknown → shown as `modern`) | SettingsProvider | Appearance › Player Screen Aesthetics | yes |
| `prefer_local_artist_images` | bool | `true` | | SettingsProvider | Appearance › Player Screen Aesthetics | yes |
| `show_visualizer` | bool | `false` | | SettingsProvider | Appearance › Player Screen Aesthetics | yes |
| `use_greeting_title` | bool | `false` (set to `true` on onboarding completion) | | SettingsProvider | Appearance › Personalization | yes |
| `user_name` | String | `'User'` (set from onboarding) | any; empty input saved as `'User'` | SettingsProvider | Appearance › Personalization (visible only if greeting on) | yes |
| `immersive_mode` | bool | `false` | | SettingsProvider | not on settings screen; toggled by tapping album art on Now Playing | yes |
| `keep_playing_on_close` | bool | `false` | | SettingsProvider / AudioHandler | Playback | yes |
| `restore_last_played_song` | bool | `true` | | SettingsProvider | Playback | yes |
| `replay_gain_mode` | String | `'off'` | `off`, `track`, `album` (unknown → off) | SettingsProvider | Playback | yes |
| `pause_on_duck` | bool | `false` | | SettingsProvider | Playback | yes |
| `pause_on_disconnect` | bool | `true` | | SettingsProvider | Playback | yes |
| `resume_on_connect` | bool | `false` | | SettingsProvider | Playback | yes |
| `mfx_bluetoothMfxPatch` | bool | `false` | | PlayerProvider (player_mfx) | Playback | yes |
| `sleep_timer_default_minutes` | int | `5` | 5,10,15,20,25,30,45,60 (invalid → shown as 60) | SettingsProvider | Playback | yes |
| `sleep_timer_fade_out_seconds` | int | `10` | 0 (Off), 10, 30, 60 (invalid → shown as 10) | SettingsProvider | Playback | yes |
| `sleep_timer_finish_song` | bool | `false` | | SettingsProvider | Playback | yes |
| `default_start_page` | int | `0` | 0 Songs, 1 Albums, 2 Artists, 3 Playlists | SettingsProvider | Playback | yes |
| `filter_title_features` | bool | `false` | | SettingsProvider | Library Formatting | yes |
| `filter_title_artist` | bool | `false` | | SettingsProvider | Library Formatting | yes |
| `auto_check_updates` | bool | `true` | | SettingsProvider | Info & Support (Android only) | yes |
| `song_sort_by` | String | `'title'` | `title`, `artist`, `duration`, `recent`, `plays` | SettingsProvider | Home sort sheet (not settings) | yes |
| `song_sort_ascending` | bool | `true` | | SettingsProvider | Home sort sheet | yes |
| `album_sort_by` | String | `'name'` | `name`, `artist`, `tracks`, `recent` | SettingsProvider | Home sort sheet | yes |
| `album_sort_ascending` | bool | `true` | | | | yes |
| `artist_sort_by` | String | `'name'` | `name`, `albums`, `songs` | SettingsProvider | Home sort sheet | yes |
| `artist_sort_ascending` | bool | `true` | | | | yes |
| `playlist_sort_by` | String | `'name'` | `name`, `songs` | SettingsProvider | Home sort sheet | yes |
| `playlist_sort_ascending` | bool | `true` | | | | yes |
| `favorites_sort_by` | String | `'date'` | `name`, `duration`, `date` | SettingsProvider | Favorites screen sort sheet | yes |
| `favorites_sort_ascending` | bool | `false` | | | | yes |
| `song_activity_view` | String | `'all'` | `all`, `recentlyPlayed`, `mostPlayed`, `leastPlayed` (derived: when `song_sort_by=='plays'` → asc ⇒ `leastPlayed`, desc ⇒ `mostPlayed`, else `all`) | SettingsProvider | derived from sort | yes |
| `colorless_artwork_paths` | StringList | `[]` | artwork paths that produced no color | PlayerProvider | none (cache) | no |
| `cached_changelog_content` | String | unset | CHANGELOG.md body | UpdateService | none (cache) | no |
| `last_changelog_check_time` | String (ISO-8601) | unset | | UpdateService | none (cache) | no |
| `onboarding_completed` | bool | `false` | | app.dart | none | no |
| `scan_folder_path` | String | unset | | MusicScanner | Settings › Library Sync | no |
| `last_sync_time` | String (pre-formatted display text) | unset | | MusicScanner | Settings › Library Sync "Last Sync" | no |
| `last_sync_timestamp` | int (epoch ms) | unset | | MusicScanner | Debug screen; drives 30-day re-sync prompt | no |
| `last_sync_duration_last` | int (ms) | unset | `last_sync_duration_<method>` | MusicScanner | Settings › Library Sync "in Xms" | no |
| `last_perf_log` | String | unset | benchmark log text | MusicScanner | Settings › Library Sync (debug-ish button) | no |
| `postpone_sync_until` | int (epoch ms) | 0 | now + 30 days on "postpone" | app.dart | home sync banner | no |

Legacy keys only in backup list (`sort_by`, `sort_ascending`, `sort_by_songs`, `sort_ascending_songs`, `eq_current_preset`, `eq_custom_gains`, `eq_enabled`, `volume`, `speed`, `mfx_*`) belong to other areas (player/MFX audit).

---

## 2. Settings screen structure (`/settings`, `SettingsScreen`)

AppBar: title `settings` ("Settings"), centered, back arrow (`closeRoute`). Body is a `ListView`:

1. **Section header** `librarySync` ("Library Sync") — primary color, bold titleSmall.
2. **Library Sync card** (rounded 20, surfaceContainerLow, outlineVariant border):
   - Folder icon in primaryContainer circle.
   - Title: `settingsProvider.scanFolder` or `setMusicDirectory` ("Set Music Directory"); subtitle `activeSyncLocation` ("Active sync location") only if a folder is set.
   - OutlinedButton: iOS → `addMusic`; Android → `setSyncFolder` (no folder) or `change`. Calls `onConfigureFolder` → SAF/`FilePicker.getDirectoryPath()` (Android) / pick audio files into app library (iOS) → `setScanFolder` → `importFromFolder` → refresh; snackbar (hardcoded English) "Sync folder configured. Imported N new song(s) (K unique themes pre-computed)!".
   - Body text `syncExplanation`.
   - Dashed divider (custom drawn).
   - "Last Sync" (`lastSync`) label + value `lastSyncTime` or `neverSynced`; if `lastSyncDuration` → `syncDuration(duration)` (" in {duration}ms").
   - If scan folder set: FilledButton.tonalIcon `syncNow` / `syncing` (spinner while running). Runs `_syncSongsSilently` (`MusicScanner.syncLibrary()`), then snackbar `syncedXSongs(count, durationText)` where durationText is hardcoded English `' in {ms}ms'`.
   - If `last_perf_log` non-empty: OutlinedButton "Copy Performance Benchmark Logs" (hardcoded English) → AlertDialog "Performance Benchmark Logs" with monospace SelectableText, actions "Copy Logs" (clipboard + snackbar "Performance log copied to clipboard!") and "Close".
3. **Section header** `stats` ("Stats").
4. ListTile `viewStatistics` / `viewStatisticsSubtitle`, icon analytics_outlined, chevron → `/stats`.
5. Divider.
6. ListTile `appLanguage`, icon language; subtitle = current: `en` → "English", `ja` → "日本語" (each language's own `arbLanguage`), else `systemDefault`. Tap → SimpleDialog `selectLanguage` with 3 radio-style rows (System Default, English, 日本語); tap saves `app_locale` and closes.
7. Divider.
8. Sub-screen navigation tiles (icon, title, subtitle, chevron):
   | Icon | Title key | Subtitle key | Route |
   |---|---|---|---|
   | palette_outlined | `appearance` | `appearanceSubtitle` ("Theme, colors, visualizer, local images") | `/settings/appearance` |
   | title_rounded | `libraryFormatting` | `formattingSubtitle` | `/settings/formatting` |
   | play_circle_outline_rounded | `playback` | `playbackSubtitle` ("Sleep timer, start page, background play") | `/settings/playback` |
   | privacy_tip_outlined | `privacySettings` ("Privacy & Permissions") | `privacySubtitle` | `/settings/privacy` |
   | settings_backup_restore_rounded | `backupRestore` | `backupRestoreSubtitle` | `/settings/backup` |
   | info_outline_rounded | `infoSettings` ("Info & Support") | `infoSubtitle` ("About, updates, changelog, danger zone") | `/settings/info` |
9. **Debug builds only (`kDebugMode`)**: Divider, header "Developer Tools" (tertiary color, hardcoded), tile "Debug Cache Info" (hardcoded; subtitle "View raw formatted cache data, local artist images, and sync state") → pushes `DebugCachesScreen` with `MaterialPageRoute` (not a go_router route).

The settings screen does not itself contain toggles except language.

---

## 3. Appearance (`/settings/appearance`, `AppearanceSettingsScreen`)

AppBar `appearance`, back arrow. ListView sections separated by `Divider(height: 28)`; headers are primary-colored bold titleSmall.

| # | Section header (l10n) | Control | Label key / subtitle key | Pref key | Type / default | Values | Effect |
|---|---|---|---|---|---|---|---|
| 1 | `themeMode` "Theme Mode" | 3-segment SegmentedButton (haptic selectionClick) | segments: `systemDefault` (brightness_auto), `light` (light_mode), `dark` (dark_mode) | `theme_mode` | String / `system` | system/light/dark | `MaterialApp.themeMode` |
| 2 | `themeColorsHeader` "Theme Colors & Accent" | 3-segment SegmentedButton | `colorSourceMaterialYou` (palette_outlined), `colorSourceAlbumArt` (album), `colorSourceCustom` (colorize) | `use_material_you` + `use_dynamic_theme` | enum derived, default **albumArt** | materialYou / albumArt / custom | Seed color for whole app theme (see §9) |
| 2a | (card under segment, depends on source) | Material You: wallpaper swatch circle (32dp, if color known) + `wallpaperColorActive` label + `colorSourceMaterialYouDesc` | — | — | — | — | info only |
| 2b | | Album Art: `colorSourceAlbumArtDesc`, then `fallbackColorHeader` "Fallback Color (when no song is playing)" + ThemeColorSelector | — | `default_theme_color` | int / 0xFF7C4DFF | colors from selector | Used when no song / song has no artwork / extraction failed |
| 2c | | Custom: `colorSourceCustomDesc`, `customColorHeader` "Choose Accent Color" + ThemeColorSelector | — | `default_theme_color` (same key!) | | | Static seed color |
| 3 | `displayAndContrast` | Switch (dark_mode_outlined) | `amoledDark` / `amoledDarkSubtitle` | `amoled_dark` | bool / false | | Dark theme surfaces forced to near-black (see §9) |
| 3 | | Switch (blur_on) | `blurredBackgrounds` / `blurredBackgroundsSubtitle` | `blurred_backgrounds` | bool / false | | Now Playing background = blurred artwork (`BlurredCoverBackground`) instead of primary→tertiary gradient (alpha .2/.1); lyrics scrim uses `BackdropFilter blur 18` instead of solid 92–94% black/white; also used in `widgets/blurred_cover_background.dart` (detail screens) |
| 4 | `playerScreenAesthetics` | ListTile + DropdownButton (art_track) | `nowPlayingStyle` / `nowPlayingStyleSubtitle`; items `styleModern`, `styleVinyl`, `styleMinimalist` | `now_playing_style` | String / modern | modern, vinyl, minimalist | Now Playing art: vinyl → `AnimatedVinyl` (spinning, size 75% width max 270 or full when immersive); minimalist → art width 55% max 200; modern → 80% max 300 (or full width in immersive) |
| 4 | | Switch (account_box_outlined) | `preferLocalArtistImages` / `...Subtitle` | `prefer_local_artist_images` | bool / true | | Library uses `artist.jpg`-style images found in music folders for artist art (`player_library.dart`) |
| 4 | | Switch (bar_chart) | `showAudioVisualizer` / `...Subtitle` | `show_visualizer` | bool / false | | Shows animated visualizer in Now Playing |
| 5 | `personalization` | Switch (wb_sunny) | `useGreetingTitle` / `...Subtitle` | `use_greeting_title` | bool / false | | Home app bar title becomes `goodMorning/goodAfternoon/goodEvening(name)` instead of app name |
| 5 | | ListTile (badge) shown only when greeting on | `yourName`, subtitle = current name | `user_name` | String / 'User' | trimmed; empty → 'User' | Name in greeting + Info "Thank you, {name}" (if name != 'user' case-insensitive) |

Name dialog: AlertDialog `enterYourName`, autofocus TextField (words capitalization, hint `yourName`), actions `cancel` / `save`.

ThemeColorSelector details (`lib/widgets/theme_color_selector.dart`): horizontal ListView height 80, 24dp side padding, 16dp gaps; each item a 56dp circle, border 3dp primary when selected (else outlineVariant), inner 4dp padding, painted as a **3-slice pie (120° each) of `ColorScheme.fromSeed(seed, dark).primary / secondary / tertiary`** starting at -90°. Supports `enabled=false` (opacity .38 + tap tooltip) but appearance screen never disables it.

Note: both Album Art and Custom modes write the **same** `default_theme_color`.

---

## 4. Library Formatting (`/settings/formatting`, `FormattingSettingsScreen`)

AppBar `libraryFormatting` (no explicit back button; default). Body:

1. Header `preview` "Preview" (titleMedium, primary).
2. Preview card: `originalMetadata` "Original Metadata:" + struck-through original title `"Awesome Artist - The Greatest Hit (feat. Someone)"` (artist "Awesome Artist"); `displayedAs` "Displayed As:" + `Song.displayTitle` live result.
3. Header `titleFilters`.

| Control | Label / subtitle keys | Pref key | Type / default | Effect |
|---|---|---|---|---|
| Switch | `filterTitleFeatures` "Remove (feat.) from titles" / `filterTitleFeaturesSubtitle` | `filter_title_features` | bool / false | `displayTitle` removes regex `\s*[\(\[](?:feat|featuring|ft)\.?\s+[^\]\)]+[\)\]]` (case-insensitive) |
| Switch | `filterTitleArtist` "Remove artist from titles" / `filterTitleArtistSubtitle` | `filter_title_artist` | bool / false | removes trailing `\s*[—–-]\s*<artist>\s*$`, else leading `^\s*<artist>\s*[—–-]\s*`; if result empty keep previous |

`Song.displayTitle` reads `SettingsProvider()` singleton globally; used everywhere titles are shown (lists, now playing, debug screen). Raw `title` is unaffected (search/sort use raw? — check in library audit).

---

## 5. Playback (`/settings/playback`, `PlaybackSettingsScreen`)

AppBar `playback`, back arrow. No section headers; two groups separated by a `Divider`.

Group 1:
| Control | Icon | Label / subtitle keys | Pref key | Type / default | Values | Effect |
|---|---|---|---|---|---|---|
| Switch | play_circle_outline | `keepPlayingOnClose` / `keepPlayingOnCloseSubtitle` | `keep_playing_on_close` | bool / false | | `SonoraAudioHandler.onTaskRemoved()`: if false → `player.stop()` + `super.onTaskRemoved()` (service stops when swiped from recents); if true → nothing (keeps playing) |
| Switch | history | `restoreLastPlayedSong` "Remember last played song" / subtitle | `restore_last_played_song` | bool / true | | On launch `player_persistence` restores last song + queue only if true |
| Dropdown | graphic_eq | `replayGain` "Volume levelling" / `replayGainSubtitle` | `replay_gain_mode` | String / off | off (`off`), track (`replayGainTrack`), album (`replayGainAlbum`) | `audioHandler.setReplayGainMode` |
| Switch | notifications_paused | `pauseOnDuck` "Pause on notifications" / subtitle | `pause_on_duck` | bool / false | | `audioHandler.setPauseOnDuck` — pause instead of duck on transient focus loss |
| Switch | headset_off | `pauseOnDisconnect` / subtitle | `pause_on_disconnect` | bool / true | | `audioHandler.setPauseOnDisconnect` (becoming-noisy / BT disconnect) |
| Switch | headset | `resumeOnConnect` / subtitle | `resume_on_connect` | bool / false | | `audioHandler.setResumeOnConnect` |
| Switch | bluetooth_audio | `mfxBluetoothPatch` "Bluetooth Audio Device Patch" / subtitle | `mfx_bluetoothMfxPatch` | bool / false | | `PlayerProvider.setBluetoothMfxPatchEnabled`, passed as `isBluetoothPatch` to MFX application (see MFX audit) |

Group 2:
| Control | Icon | Label / subtitle keys | Pref key | Type / default | Values (display) | Effect |
|---|---|---|---|---|---|---|
| Dropdown | timer_outlined | `defaultSleepTimer` / subtitle | `sleep_timer_default_minutes` | int / 5 | 5,10,15,20,25,30,45,60 shown with `minuteAbbr(min)` "{min} min" | Preselected duration in `sleep_timer_sheet.dart` |
| Dropdown | volume_down | `sleepTimerFadeOut` / subtitle | `sleep_timer_fade_out_seconds` | int / 10 | 0 → `off`, else `secondsAbbr(sec)` "{seconds}s": 10, 30, 60 | Fade-out length used by `startSleepTimer` |
| Switch | music_note | `finishCurrentSong` / subtitle | `sleep_timer_finish_song` | bool / false | | Default for "finish song before stopping" in sleep timer |
| Dropdown | home_outlined | `defaultStartPage` / subtitle | `default_start_page` | int / 0 | 0 `songs`, 1 `albums`, 2 `artists`, 3 `playlists` | Home TabController `initialIndex` |

---

## 6. Privacy & Permissions (`/settings/privacy`, `PrivacySettingsScreen`) — static, no settings

Card layout: surfaceContainerLow, radius 16, border; icon (primary) + title (titleMedium w600) + body (bodyMedium, onSurfaceVariant, line height 1.5).

1. Card security_rounded: `privacyCardDataTitle` "Your Data is Yours" / `privacyCardDataContent` ("This app operates entirely offline and communicates with no servers, with the exception of checking GitHub for app updates. … never sent anywhere. \n\nYou can trust that your listening habits remain private.")
2. Header `permissionsExplained` "Permissions Explained"
3. Card folder_open: `privacyCardStorageTitle` "Storage, Audio & Cover Images" / `privacyCardStorageContent` (scans selected folder for audio and artist/album art like artist.jpg/cover.png; promises "Photos & Media" permission is NOT used to read gallery).
4. Card notifications_active: `privacyCardNotificationsTitle` "Notifications & Foreground Service" / content (media controls in shade + lock screen; foreground service for background playback).
5. Card battery_charging_full: `privacyCardWakeLockTitle` "Wake Lock" / content.
6. Card public: `privacyCardInternetTitle` "Internet" / content ("Only used to fetch the latest release version and changelog from GitHub …").
7. Header `dataManagement` "Data Management"
8. Card delete_sweep: `privacyCardDeleteDataTitle` "Delete All Data" / content (points to Danger Zone in Info & Support).

Port note: these texts describe the Android permission set: READ_MEDIA_AUDIO/IMAGES (or READ_EXTERNAL_STORAGE), POST_NOTIFICATIONS, FOREGROUND_SERVICE(_MEDIA_PLAYBACK), WAKE_LOCK, INTERNET (+ REQUEST_INSTALL_PACKAGES for updater). Keep them consistent with the Kotlin manifest.

---

## 7. Backup & Restore (`/settings/backup`, `BackupSettingsScreen`)

AppBar `backupRestore` with a 4dp LinearProgressIndicator in `bottom` while busy. Three cards:

1. upload_file: `backupExportTitle` / `backupExportDescription` → tap = export.
2. settings_backup_restore: `backupImportTitle` / `backupImportDescription` → tap = import.
3. info_outline (non-tappable): `backupContentsTitle` / `backupContentsContent`.

Export: get app version (`version+buildNumber`), `BackupService.exportToJson(appVersion)`, `FilePicker.saveFile(fileName: BackupService.defaultFileName(now), bytes, mime application/json)` → snackbars `backupExportSuccess` / `backupErrorLibraryEmpty` / `backupExportFailed` / `backupErrorFilePicker`.
Import: pick `.json` → parse/validate → confirm dialog (icon, `backupRestoreConfirmTitle`, `backupRestoreConfirmContent(date)` with date `DateFormat.yMMMd(locale).add_jm()`, actions `cancel` / `backupRestoreAction`) → `restoreDocument` → reload providers (`loadSettings`, push audio flags to handler, `themeProvider.reload()`, `playerProvider.reloadAfterRestore`) → snackbar joining with " · ": `backupRestoreSuccess`, `playlistCount(n)`, `backupRestoredFavorites(n)`, optional `backupUnmatchedSongs(n)`, optional `backupRestartNeeded`.
Errors map: unsupportedVersion → `backupErrorUnsupportedVersion`; invalidJson/notABackup/malformed → `backupErrorInvalidFile`; libraryEmpty → `backupErrorLibraryEmpty`; noMatchingSongs → `backupErrorNoMatchingSongs`; readFailed → `backupErrorRestoreFailed`; writeFailed → `backupErrorRolledBack`. Snackbar duration 6 s.
Settings restored = exactly the keys of `BackupService._settingKeys` (see §1 "Backed up" column); keys missing from a backup that has a settings section are **removed** (reset to default). Detailed format belongs to backup audit.

---

## 8. Info & Support (`/settings/info`, `InfoSettingsScreen`)

AppBar `infoSettings`, back arrow. On init loads `NativeBridge.getPackageInfo()` → version string `version` or `version+buildNumber` (fallback `'1.0.0'`), `isDevBuild = kDebugMode || packageName.endsWith('.dev') || version.contains('-dev')`.

Rows in order:
1. ListTile info_outline: title `aboutSonora` (+ `DevBadge` if dev) / subtitle `version(_appVersion)` "Version {version}" → **About dialog**: 80×80 `assets/icon/ic_launcher.png`, `appTitle` "Sonora" headlineSmall bold (+DevBadge), `version(...)`, `appDescription`, divider, `madeWithLove` "Made with ❤️ by yurtemre", tiny text "Flutter • Material 3" (hardcoded), action FilledButton `close`.
2. **Android only** ListTile system_update (spinner while checking): `checkForUpdates` / `checkForUpdatesSubtitle` → manual update check (§11).
3. **Android only** Switch sync_rounded: `autoCheckUpdates` "Automatically Check for Updates" / `autoCheckUpdatesSubtitle` ("Check GitHub for releases every time the app opens") → `auto_check_updates` (bool, default true).
4. ListTile history: `changelogTitle` / `changelogSubtitle` → `/changelog`.
5. ListTile description_outlined: `licenses` / `licensesSubtitle` → Flutter `showLicensePage` (applicationName "Sonora", version, icon = 64dp rounded square with primary→tertiary diagonal gradient and music_note icon). Port: use AboutLibraries / OSS licenses plugin.
6. Divider.
7. **Thank-you card**: animated beating heart (favorite icon in primaryContainer circle; 1400 ms loop: 1.0→1.15→1.02→1.10→1.0 then 40% rest), title `thankYouTitleWithName(name)` if `user_name` trimmed non-empty and != "user" (case-insensitive) else `thankYouTitle`; body `thankYouMessage`.
8. Header `communityAndSupport`.
9. ListTile code: `sourceCode` / `sourceCodeSubtitle` → open `https://github.com/yurtemre7/sonora`.
10. ListTile person: `developerProfile` / `developerProfileSubtitle` → `https://github.com/yurtemre7`.
11. ListTile send: `telegramContact` / `telegramContactSubtitle` ("Reach out via @emredev") → `https://t.me/emredev`.
    (All links via `NativeBridge.openUrl` → `Intent.ACTION_VIEW` with NEW_TASK.) No contributors list exists.
12. Divider.
13. Header `dangerZone` (error color).
14. **Danger Zone card** (errorContainer, error border alpha 0.5→1.0 with progress): warning icon, `resetApplication` "Reset Application" (error, bold), `resetApplicationSubtitle` "Hold for 3 seconds to wipe all data." Interaction: raw `Listener` pointer-down starts a 3 s AnimationController (light haptic) rendered as a LinearProgressIndicator fill (error α .2) behind content; moving beyond `kTouchSlop` or lifting/cancel reverses it; on completion → heavy haptic → `ConfirmDeleteDialog.show(title: resetApplicationConfirmTitle, message: resetApplicationConfirmMessage, confirmLabel: reset)` → on confirm `onResetApp`.

### Danger zone: what it actually wipes (`app.dart::_resetApp`)
- `SharedPreferencesAsync().clear()` → **all** prefs: settings, theme mode, sort, stats/play counts kept in prefs, favorites, playlists stored in prefs, scan folder, sync timestamps, onboarding flag, changelog cache, mfx, colorless cache, etc.
- In-memory: scanFolder=null, songs=[], `showOnboarding=true` (router → onboarding), `playerProvider.stop()`, `updateSongs([])`, `ImagePreflightService.instance.clearCache()`.
- Snackbar (hardcoded English): "Library and settings cleared successfully!".
- NOT deleted: files in app documents dir (`imported_songs.json` library cache, `artist_images.json`, thumbnails from `thumbnail_store`, playlist cover images, iOS `Documents/Music` imported audio), temp `sonora_update.apk`, the home-screen widget state, DataStore of native code. The confirm message claims "permanently delete all imported audio files and clear your library" — overstated vs implementation. The SettingsProvider/ThemeProvider in-memory values are NOT reset either (theme stays until restart). Port: implement a real wipe (clear DataStore + Room DB + filesDir/cacheDir) or `ActivityManager.clearApplicationUserData()`.
- Also a separate error-screen action "Clear App Data & Retry" (`_clearAppDataAndRestart`) clears prefs (3 s timeout) and reloads.

---

## 9. Theming

### 9.1 Theme mode
`ThemeProvider` → `theme_mode` (`system`/`light`/`dark`, default system). Applied as `MaterialApp.themeMode`. Light and dark ThemeData are both built from the same seed.

### 9.2 Color source (`ThemeColorSource` enum, derived from two bools)
```
useMaterialYou == true  → materialYou
else useDynamicTheme    → albumArt   (default: use_dynamic_theme defaults true)
else                    → custom
```
`setThemeColorSource`: materialYou → `use_dynamic_theme=false`, `use_material_you=true` (fetch wallpaper color); albumArt → `use_material_you=false`, `use_dynamic_theme=true`; custom → both false.

Seed color selection in `app.dart` build:
| Source | Seed |
|---|---|
| materialYou | `dynamicWallpaperColor ?? playerProvider.defaultThemeColor` |
| albumArt | `playerProvider.themeColorNotifier.value` (current song's color, else default) |
| custom | `playerProvider.defaultThemeColor` (`default_theme_color`, default `#7C4DFF`) |

**Material You wallpaper color**: method channel `de.yurtemre.sonora/mediastore` method `getDynamicWallpaperColor` → Kotlin returns `getColor(android.R.color.system_accent1_600)` on API ≥ 31 (S), else null. It is a single seed color re-run through `ColorScheme.fromSeed` (not the full system dynamic scheme). Refreshed when Material You is (re)selected, at `loadSettings` if enabled, and on every `AppLifecycleState.resumed` (`PlayerProvider.didChangeAppLifecycleState`). Port: use `dynamicLightColorScheme/dynamicDarkColorScheme` (Compose) — note behavioral difference (Flutter re-derives scheme from one accent).

### 9.3 Custom accent / fallback colors — there is NO fixed palette
`PlayerProvider.getUniqueThemeColors()` builds the selector list dynamically:
- Always first: `#7C4DFF` (deep purple, the app seed).
- Then for every song with a cached `dominantColor`, bucket by HSL: if saturation < 0.15 or lightness < 0.15 or lightness > 0.85 → bucket `neutral`; else `hue_<round(hue/30)*30 % 360>` (12 hue sectors). First color seen per bucket is added. So max 1 + 13 colors, all from the user's album art.
- Consequence: on a fresh install with no artwork colors extracted, the only choice is `#7C4DFF`.
- `AppTheme.precomputeThemes(songs)` uses the same bucketing only to return a count (`uniqueThemeCount`, shown in the onboarding/folder snackbar); despite the name it does not build ThemeData.

Hardcoded colors in this area: seed `0xFF7C4DFF`; AMOLED surfaces `#000000`, `#050505`, `#0A0A0A`, `#101010`, `#151515`; widget colors (§14); rate-limit warning icon `Colors.orange`.

### 9.4 Album-art color extraction (`ColorExtractor.extractDominantColor`)
- Decode artwork file with `ui.instantiateImageCodec(bytes, targetWidth: 32, targetHeight: 32)` (native downsample to 32×32).
- Read RGBA bytes; average R, G, B over all pixels with alpha > 128 (comment says "skip dark" but it only skips transparent). If no opaque pixel → null.
- Result `Color(255, avgR, avgG, avgB)` — a plain **mean color**, not a Palette/vibrant swatch. Port: `Bitmap.createScaledBitmap(…,32,32,true)` + average to reproduce exactly; androidx Palette would give different (more vivid) results.
- Per-song flow (`_extractThemeColorForSong`, on song change/restore): if albumArt mode off → apply default; no artwork → default; cached `song.dominantColor` → use it; path in `colorless_artwork_paths` → default; else extract; success → apply + cache to song (`MusicScanner.saveDominantColor`, persisted in library cache JSON); failure → add path to colorless set (persisted in prefs `colorless_artwork_paths`).
- Background pass (`_startBackgroundColorExtraction`): 3 s delay after library load, groups songs by artworkPath (skip already colored or colorless), extracts each unique image with 100 ms sleep between, then applies all at once, refreshes snapshots and saves all song metadata. Cancellable via `_isExtractingColors`.
- `_applyThemeColor(color)` sets `settingsProvider.dynamicThemeColor` (in memory) and `themeColorNotifier.value` → rebuilds `MaterialApp` with new seed (ThemeData cached per seed in `AppTheme._lightThemeCache/_darkThemeCache`, key = ARGB, XOR 0xFFFFFFFF for AMOLED dark).

### 9.5 ThemeData (`AppTheme.buildTheme`)
- Material 3, `ColorScheme.fromSeed(seed, brightness)` (Flutter's default `DynamicSchemeVariant.tonalSpot`). Port: Material Color Utilities `SchemeTonalSpot` / `dynamicColorScheme(seed)` to match.
- AMOLED (dark only): surface `#000000`, surfaceContainerLowest `#000000`, Low `#050505`, (default) `#0A0A0A`, High `#101010`, Highest `#151515`.
- Typography: **Outfit** for display*/headline*/title* styles, **Inter** for body*/label*; both bundled in `assets/fonts` (Outfit 400/500/600/700/900, Inter 400/500/600/700). Text colors = onSurface. AppBar title: Outfit w600 22sp.
- Component overrides: AppBar transparent, elevation 0, scrolledUnderElevation 0, centered title, transparent status/nav bars with icon brightness by theme; Card color surfaceContainerHighest, elevation 0, radius 24, zero margin; Slider trackHeight 3, thumb radius 6, overlay 14, inactive onSurface α .12; BottomSheet surfaceContainerHigh, top radius 20; ListTile horizontal padding 16, radius 12; Divider outlineVariant α .3, thickness .5; Progress indicator primary, transparent linear track; Scrollbar thickness 8, radius 8, interactive, thumb primary (α .5 idle).
- Edge-to-edge: `AnnotatedRegion` transparent nav bar + divider.

### 9.6 DevBadge (`lib/widgets/dev_badge.dart`)
Small pill "DEV" (labelSmall bold, letterSpacing .8, default 10sp) on tertiaryContainer, radius 6, border tertiary α .4 0.8dp. Shown next to "About Sonora" and in the About dialog when `isDevBuild`. Debug build: `applicationIdSuffix .dev`, `versionNameSuffix -dev`, app name "Sonora Dev".

---

## 10. Language selection / localization runtime

- Options: System Default (`system`), English (`en`), 日本語 (`ja`). Labels for languages come from each locale's `arbLanguage` key (so always shown in their own language).
- Stored in `app_locale` (default `system`). `SettingsProvider.currentLocale` → `Locale('en'|'de'|'ja')` or null (system). `de` is a dead branch (no German strings → resolves to English).
- `MaterialApp.locale = currentLocale`, `supportedLocales = [en, ja]`, delegates `AppLocalizations.localizationsDelegates + GlobalMaterialLocalizations.delegates`. Template ARB is English; `nullable-getter: false`.
- `localizationsFor(Locale?)` (`lib/utils/l10n_extension.dart`) for non-UI code (notifications, sleep timer notification): uses preferred locale or `PlatformDispatcher.locale`, resolves with `basicLocaleListResolution` → falls back to English.
- Change applies immediately (MaterialApp rebuild). Port: use AppCompat per-app language (`AppCompatDelegate.setApplicationLocales`) + `locales_config.xml`; keep an in-app picker and persist `system/en/ja`.
- Hardcoded English strings remain in several places (not localized): app.dart snackbars/loading/error/permission screens, perf-log dialog, Developer Tools, debug caches screen, UpdateDialog ("Architecture / APK Package", "Recommended", "Update Now", "Preparing download...", "Downloading update... N%", "Opening installer...", "Download failed. Tap to try via browser.", tooltip "Open link in browser"), changelog fallback "No changelog available.", update default body "No changelog provided.", About "Flutter • Material 3".

---

## 11. Update service (Android only) — `lib/services/update_service.dart`

Endpoints:
- Release API: `GET https://api.github.com/repos/yurtemre7/sonora/releases/latest` with header `Accept: application/vnd.github.v3+json` (unauthenticated → 60 req/h/IP).
- Changelog: `GET https://raw.githubusercontent.com/yurtemre7/sonora/main/CHANGELOG.md`.
- One shared `http.Client` (connection reuse).

`checkForUpdate()` flow:
1. GET release. 403 or 429 → `UpdateResult(isRateLimited: true)`.
2. 200 → parse `tag_name`, `body` (default "No changelog provided."), `html_url`. If tag or html_url missing → error.
3. `_isVersionNewer(tag)`: local = `PackageInfo.version` split at `+` (Android `versionName`, e.g. `1.28.1` or `1.28.1-dev` in debug); remote = tag trimmed, strip leading `v`/`V`, strip `+build`. Compare first 3 dot-separated parts as ints (`int.tryParse ?? 0`; so `1-dev` → 0). Newer only if remote > local. Build numbers ignored entirely.
4. If newer: force-refresh changelog cache (`fetchChangelog(forceRefresh: true)`), then map assets by lowercase name substring: contains `arm64-v8a` → arm64-v8a; `armeabi-v7a`; `x86_64`; `x86` (checked last so x86_64 wins). Value = `browser_download_url`.
5. ABI pick: `getDeviceAbi()` via channel `de.yurtemre.sonora/volume` method `getDeviceAbi` (Kotlin: `Build.SUPPORTED_ABIS[0]` normalized: contains arm64/aarch64 → `arm64-v8a`; contains v7 or arm → `armeabi-v7a`; x86_64; x86; else raw). If detected ABI present → that; else arm64-v8a if present; else first asset; else no assets → `recommendedUrl = html_url`, `recommendedAbi = null`.
6. Returns `UpdateInfo(version: tag, changelog: release body, downloadUrl: html_url, apkAssets, recommendedAbi, recommendedUrl)`. Not newer → `UpdateResult()` (no update). Any exception / other status → `hasError`.

Pre-release handling: uses `/releases/latest`, which GitHub defines as the latest non-draft, non-prerelease release; the workflow always publishes `prerelease: false`. No channel/beta support. Version suffixes like `-beta` would parse as 0 in that component.

When it checks:
- Automatically: `HomeScreen.initState` post-frame → `_checkUpdateAutomatically()`; skipped if `kDebugMode`, non-Android, or `auto_check_updates == false`. Runs on every HomeScreen creation (each app launch / home rebuild after onboarding). **No interval, no throttle, no "skip this version"**. Rate-limit and errors are silent; only a found update shows `UpdateDialog`.
- Manually: Info › Check for Updates. Results: rate limited → AlertDialog (warning_amber orange icon, `rateLimitTitle`, `rateLimitMessage`, actions `cancel` and FilledButton.icon `openGithubReleases` → open `https://github.com/yurtemre7/sonora/releases`); error → snackbar `updateCheckFailed`; update → `UpdateDialog`; none → snackbar `alreadyOnLatest`.

Download/install (`UpdateDialog` + service):
- Dialog: title row (system_update icon primary + `updateAvailable`), `updateAvailableMessage(tag)` (tag includes `v` and `+build`, e.g. "Version v1.28.1+412 is now available!"), when assets exist and not downloading: header "Architecture / APK Package" + `ChoiceChip` per ABI (with "Recommended" pill on recommended ABI); selection changes `_selectedUrl`. Changelog label `changelogLabel` + scrollable box with the **release body** (plain text, not markdown-rendered).
- Actions (idle): `later` (close), IconButton open_in_browser (opens selected URL in browser, closes), FilledButton.icon "Update Now". While downloading: only `cancel` (closes dialog; download is not actually cancelled).
- `downloadApk(url, onProgress)`: deletes existing `<cacheDir>/sonora_update.apk` (`getTemporaryDirectory()`), streams GET (redirects followed), non-200 → drain + throw; writes chunks, progress = received/contentLength (indeterminate if unknown); on stream error closes sink, deletes partial file, rethrows.
- `installApk(path)`: channel `de.yurtemre.sonora/volume` method `installApk` → Kotlin `FileProvider.getUriForFile(ctx, "${packageName}.fileprovider", file)` (API ≥ 24), `ACTION_VIEW` with type `application/vnd.android.package-archive`, flags NEW_TASK | GRANT_READ_URI_PERMISSION. Returns true when the intent was started (not when installed). If false → fallback opens `_selectedUrl` in browser. Then dialog pops.
- Error → shows red text "Download failed. Tap to try via browser." (there is no tap handler on that text; user must use the browser icon after state resets).
- Requires manifest `REQUEST_INSTALL_PACKAGES` and a FileProvider with cache-path (check manifest/xml in platform audit). No checksum/signature verification of the APK (Android's package installer enforces same signing key).

`fetchChangelog({forceRefresh})`: cache in prefs `cached_changelog_content` + `last_changelog_check_time` (ISO string); if not forced and last check < 12 h and cache present → return cache; else GET raw CHANGELOG.md; on 200 decode UTF-8, store both keys, return; non-200/exception → null (stale cache is NOT used as fallback on failure).

Tests (`test/update_service_test.dart`): download writes full file + progress, follows redirects, deletes partial on mid-stream failure, non-200 throws and leaves no file; changelog UTF-8 + 12 h cache, forceRefresh bypass, null + no cache on non-200/unreachable; checkForUpdate newer (assets map keys order `['arm64-v8a','x86_64']`, recommended arm64, changelog fetched), older tag → up to date, 403/429 → rate limited, 500/unreachable → error.

---

## 12. Changelog screen (`/changelog`, `ChangelogScreen`)

- Source: `UpdateService.fetchChangelog()` (raw GitHub `main/CHANGELOG.md`, 12 h prefs cache). Not bundled in the app — offline first launch shows error.
- AppBar `changelogTitle` + refresh IconButton → `fetchChangelog(forceRefresh: true)`.
- States: loading spinner; error → error_outline 48dp (error color), `failedToLoadChangelog`, FilledButton.tonal `tryAgain` (non-forced reload); success → `flutter_markdown_plus` `Markdown` widget in SafeArea, styles: p = bodyMedium; h1 = headlineMedium primary bold; h2 = titleLarge primary bold; h3 = titleMedium bold. Fallback text "No changelog available.".
- Port: Markdown renderer (e.g. compose-markdown / Markwon) with matching h1/h2/h3 styling; content is the whole file (all versions).

CHANGELOG.md format (first lines):
```
# Changelog

All notable changes to the Sonora music player project are documented in this file.

## [1.28.1] - 2026-11-07
### Changed
* Chore: Update dependencies

## [1.28.0] - 2026-11-05
### Added
* Add blurred backgrounds setting and cheaper blur
...
### Fixed
* ...
### Changed
* Docs: ... / Chore: ... / Refactor: ... / Style: ...
```
Keep-a-Changelog-like: `## [x.y.z] - YYYY-MM-DD`, subsections in order Added, Fixed, Changed, bullets with `* `. 775 lines.

---

## 13. Release workflow & changelog script

### `.github/workflows/release.yml` ("🚀 Flutter Release Build")
Triggers: push to `main`, or `workflow_dispatch`. Concurrency group `release-<ref>`, cancel in progress.

Jobs:
1. **version** (ubuntu): runs only if manual dispatch or head commit message contains `release:`. Checkout full history; `VERSION` = pubspec `version:` before `+`; `BUILD_NUMBER = git rev-list --count HEAD`; `TAG = v${VERSION}+${BUILD_NUMBER}` (e.g. `v1.28.1+412`). Outputs version_name, build_number, tag.
2. **build-apk** (ubuntu, needs version): Flutter from `.fvmrc` (stable, cached), Java Temurin 21, setup-gradle, cache CMake 3.22.1 and NDK 28.*. Signing: if secret `KEYSTORE_BASE64` set → decode to `android/app/keystore.jks` and write `android/app/key.properties` (storeFile=keystore.jks, storePassword=secret STORE_PASSWORD, keyAlias=`upload`, keyPassword=secret KEY_PASSWORD). `flutter pub get`; `flutter build apk --release --split-per-abi --build-name=VERSION --build-number=BUILD_NUMBER`; rename to **`sonora-armeabi-v7a.apk`, `sonora-arm64-v8a.apk`, `sonora-x86_64.apk`** (no x86 32-bit). Upload artifact `sonora-android-apk`.
3. **build-ipa** (macos-latest): `flutter build ios --release --no-codesign`, zip `Payload/Runner.app` → **`sonora-ios.ipa`**, artifact `sonora-ios-ipa`.
4. **release** (ubuntu, needs all; runs if not cancelled and build-apk succeeded — iOS failure does not block): checkout; download artifacts `sonora-*` merged into `dist/`; extract notes: `awk '/^## \[VERSION\]/{flag=1; next} /^## \[/{flag=0} flag' CHANGELOG.md > release_notes.md`, fallback body `Release <tag>` if empty; `softprops/action-gh-release@v3.0.2` with tag_name = tag, name `Sonora <tag>`, draft false, prerelease false, `body_path: release_notes.md`, `generate_release_notes: true` (GitHub appends auto-generated PR/commit notes), files `dist/sonora-*.apk`, `dist/sonora-*.ipa`. Permission contents: write.

Consequences for the in-app updater: tag carries `+build` (stripped in compare); release body = CHANGELOG section + GitHub auto notes (displayed raw in dialog); asset names contain the ABI string the updater matches.

### `android/app/build.gradle.kts`
- namespace/applicationId `de.yurtemre.sonora`; compileSdk 37, minSdk 24, targetSdk 37; Java/Kotlin 17; core library desugaring (`desugar_jdk_libs 2.1.5`); `androidx.window:window(-java):1.0.0`.
- versionCode/versionName from Flutter (`--build-number` = git commit count, `--build-name` = pubspec version).
- `manifestPlaceholders["appName"] = "Sonora"`; debug: suffix `.dev`, versionNameSuffix `-dev`, appName "Sonora Dev", debug signing.
- Release signing from `key.properties` — loaded **unconditionally** at configuration time (`load(FileInputStream(file("key.properties")))`), so any build fails if the file is missing (CI only writes it when the secret exists). Release uses proguard-android-optimize + `proguard-rules.pro`.

### `scripts/generate_changelog.dart` (run `fvm dart scripts/generate_changelog.dart` from repo root; manual, not in CI)
1. Read pubspec version (before `+`) → current bucket name (or `Unreleased`), dated today.
2. `git log --pretty=format:%h|%as|%s` (newest first).
3. For each commit subject:
   - Release commit `^release:\s*v?(\d+\.\d+\.\d+)` (case-insensitive): any trailing description (not purely digits) is added to the *current* (newer) bucket's Changed; then the current bucket switches to that version (dated by that commit's date). I.e. commits below a `release: X` commit belong to version X.
   - Conventional `type(scope): msg`: `feat` → Added; `fix` → Fixed; `refactor|style|perf|docs|chore` → Changed as `"Type: Msg"`; any other type → Changed (msg only). Message capitalized.
   - Non-conventional: starts with "add" or contains "implement" → Added; starts with "fix" or contains "prevent" → Fixed; else Changed unless it contains "merge branch"/"bump version".
4. Preserves manual sections: existing `## [ver] - date` sections in CHANGELOG.md; their `*` bullets are only emitted if the generated bucket for that version is empty; versions only in the old file are appended.
5. Writes header `# Changelog` + description, then per version `## [v] - date`, `### Added/Fixed/Changed` with de-duplicated `* ` bullets; skips empty versions. Overwrites CHANGELOG.md.

Release process (README "Releases"): commit to `main` with `release:` in the message (e.g. `release: 1.28.1`, recent history: `release: 1.28.1`), after bumping pubspec version and regenerating the changelog. Note: git status reports the repo default as `master` while workflow and changelog URL use `main`.

README build section: FVM Flutter 3.47.9 (`.fvmrc`), Dart ^3.13.0; Android SDK 24+, compile/target 37, JDK 17+; `fvm install`, `fvm flutter pub get`, `fvm flutter analyze`, `fvm flutter test`; `fvm flutter run`, `fvm flutter build apk --release`; Windows `fvm flutter build windows --release`; iOS unsigned IPA steps. Install table lists the 3 APKs (arm64 recommended).

---

## 14. Android resources (`android/app/src/main/res/values*`)

- `values/strings.xml` + `values-ja/strings.xml`: home-screen widget strings only: `widget_label` (Now playing / 再生中), `widget_description`, `widget_nothing_playing`, `widget_tap_to_open` ("Tap to open Sonora"), `widget_album_art`, `widget_play`, `widget_pause`, `widget_previous`, `widget_next`. App name is a manifest placeholder, not a string resource.
- `values/styles.xml` / `values-night/styles.xml`: `LaunchTheme` (Theme.Light/Black.NoTitleBar, windowBackground `@drawable/launch_background`) and `NormalTheme` (windowBackground `?android:colorBackground`, draws system bars, transparent nav bar).
- `values/widget_colors.xml` (light, static M3 baseline purple): surface `#F3EDF7`, on_surface `#1D1B20`, on_surface_variant `#49454F`, primary `#6750A4`, on_primary `#FFFFFF`, placeholder `#E8DEF8`, on_placeholder `#6750A4`.
- `values-night/widget_colors.xml`: surface `#211F26`, on_surface `#E6E0E9`, on_surface_variant `#CAC4D0`, primary `#D0BCFF`, on_primary `#381E72`, placeholder `#4A4458`, on_placeholder `#D0BCFF`.
- `values-v31` / `values-night-v31`: same names mapped to `@android:color/system_neutral1_*`, `system_neutral2_*`, `system_accent1_*`, `system_accent2_*` (light: neutral1_50/900, neutral2_700, accent1_600/0, accent2_100/700; dark: neutral1_900/50, neutral2_200, accent1_200/800, accent2_700/100).
- `values/widget_dimens.xml`: radius 20dp, art radius 12dp, padding 8dp, art 48dp, art large 96dp; v31 radii → `@android:dimen/system_app_widget_background_radius` / `system_app_widget_inner_radius`.

---

## 15. Debug Caches screen (`DebugCachesScreen`, debug builds only)

AppBar `debugCacheInfo` (bold) + refresh action (`refresh` tooltip) → reloads. Read-only: **does not clear anything**. Loading: `getLastSyncTime`, `getLastSyncTimestamp`, `loadLocalArtistImages()`, and a recursive file listing of the scan folder in an isolate. Sections (all hardcoded English):
1. "Environment & Build": `kDebugMode`, `kReleaseMode`, `kProfileMode`.
2. "MusicScanner & Sync State": Scan Folder (or "Not configured"), Last Sync Time (or "Never"), Last Sync Epoch (or "None").
3. "In-Memory Player Caches": Total Songs Loaded, Cached Albums, Cached Artists, Active Queue Length, User Playlists, Favorite Albums Count, Favorite Artists Count.
4. "Detected Artist Cover Images (N)": artist → image path rows, or "No local artist image mappings detected in cache."
5. "All Scanned Music Files (N)": per song `displayTitle (#id)`, duration, `artist • album`, filePath (monospace tertiary); empty text.
6. "All Physical Files Discovered in Root (N)": every file path under scan folder; empty text.
Port: optional; can be a debug-only screen.

---

## 16. Localization inventory

- Files: `lib/l10n/app_en.arb` (template, 741 lines, has `@key` metadata) and `lib/l10n/app_ja.arb` (446 lines, values only). Generated `app_localizations*.dart` committed. `l10n.yaml`: arb-dir lib/l10n, template app_en.arb, output app_localizations.dart, class AppLocalizations, nullable-getter false.
- **429 message keys** in each file; no missing or extra keys between en and ja. Every key also in `strings.tsv` (430 lines incl. header).
- **42 keys with placeholders**; **9 ICU plurals** (en): `albumCount`, `artistCount`, `playlistCount`, `songCount`, `trackCount`, `playsCount`, `listensCount` (has `=0{Never played}`), `backupRestoredFavorites`, `backupUnmatchedSongs`. Japanese versions of these are flat strings (`{count}曲` etc.) — note ja `listensCount` shows "0 回再生" instead of "never played". No `select` messages.
- Placeholder keys (non-plural): deletePlaylistConfirmMessage{name}, exportedPlaylist{name}, goodMorning/goodAfternoon/goodEvening{name}, minuteAbbr{min}, queueNOfM{current,total}, stopInX{duration}, syncDuration{duration}, syncedXSongs{count,duration}, thankYouTitleWithName{name}, updateAvailableMessage{version}, version{version}, mfxPercentSlower/Faster{percent}, mfxSpeedSubtitle{speed}, songsCountShort{count}, playlistDeleted{name}, noMusicFilesInFolderSubtitle{folder}, removedFromQueue{title}, moreByArtist{artist}, savedPlaylist{name}, secondsAbbr{seconds}, selectedCount{count}, batchAddedToQueue/PlayNext/Favorited/Unfavorited{count}, batchAddedToPlaylist/RemovedFromPlaylist{count,playlist}, backupRestoreConfirmContent{date}, sleepTimerMinutes{minutes}, sleepTimerRemaining{time}. Types are in the `placeholders` column of strings.tsv.
- Port mapping: ICU plurals → `<plurals>` (en one/other; ja other only); `=0` special case → handle in code. `{name}` → `%1$s` etc. `syncDuration` has a leading space (" in {duration}ms") that Android XML trims unless escaped/quoted. Some values contain `"` (e.g. `filterTitleArtistSubtitle`), `'` (`whatsInABackup`-style text "What's in a backup"), `@` (`telegramContactSubtitle` "@emredev" — must escape leading `@` only if at string start), `%` (`mfxPercent*` → `%%` in formatted strings), `?` and newlines.

Key groups by leading camelCase word (count) — full distribution:
backup 24, no 20, sort 20, mfx 19, privacy 14, reset 10, sleep 8, most 7, batch 6, color 6, replay 6, add 5, pause 5, playlist 5, sync 5, eq 5, default 4, delete 4, filter 4, search 4, show 4, app 3, change 3, changelog 3, choose 3, custom 3, failed 3, file 3, good 3, open 3, permissions 3, play 3, queue 3, remove 3, repeat 3, save 3, select 3, shuffle 3, song 3, thank 3, update 3, your 3, now 3, style 3, access 2, album 2, all 2, amoled 2, blurred 2, appearance 2, artist 2, auto 2, cancel 2, check 2, create 2, date 2, developer 2, favorite 2, info 2, keep 2, restore 2, library 2, licenses 2, lyrics 2, playback 2, prefer 2, rate 2, related 2, rename 2, selected 2, set 2, songs 2, source 2, telegram 2, theme 2, title 2, track 2, up 2, use 2, view 2, share 2, resume 2, finish 2, recently 2, and 1 each: about, active, albums, already, arb, artists, back, close, community, configure, danger, dark, data, description, disable, displayed, edit, enable, enter, experimental, export, exported, favorites, formatting, get, last, later, light, listening, made, minute, music, never, next, onboarding, original, personalization, place, playlists, plus, preset, preview, previous, scroll, refresh, settings, setup, start, stats, stop, synced, syncing, system, timer, try, version, welcome, yesterday, metadata, audio, genre, year, duration, copy, copied, format, bitrate, sample, complete, skips, restarts, fun, first, unique, plays, import, imported, removed, from, more, saved, collapse, debug, off, seconds, deselect, display, player, wallpaper, fallback, least, today, this, listens, end.

Semantic grouping (approx.): settings/appearance/playback/formatting (~70), privacy (14 `privacy*` + permissions), backup (24), sort (20), MFX/EQ (24), sleep timer (8 + stop/timer/plusOneMin…), stats (most*/least*/listens/plays/first/unique/fun…), batch multi-select (6 + selected/deselect), empty states (`no*` 20), update/changelog (~12), info/about/danger (~20), library/sync/onboarding, metadata sheet (genre/year/bitrate/sample/format/duration).

---

## 17. Tests covering this area

- `test/settings_persistence_test.dart`: defaults on fresh start (all values in §1), each ThemeColorSource persists across a new provider instance, all setters persist, ThemeProvider persists system/dark/light.
- `test/appearance_settings_test.dart`: ThemeColorSelector taps call `onColorSelected` / disabled does not; AppearanceSettingsScreen segmented buttons switch sources and show "Choose Accent Color" + selector for custom; Material You re-apply and app `resumed` lifecycle re-fetch wallpaper color via channel `de.yurtemre.sonora/mediastore` `getDynamicWallpaperColor`.
- `test/color_extraction_test.dart`: background extraction colors songs sharing an artwork (red PNG → `0xFFFF0000`), fully transparent PNG → null + recorded in `colorless_artwork_paths`.
- `test/localization_test.dart`: `localizationsFor(ja).plusOneMin == '+1分'`, en `'+1 min'`, de falls back to en; `sleepTimerMinutes(15) == '15 min'`, `sleepTimerRemaining('01:30') == '01:30 remaining'`; en/ja MaterialApp resolve both delegates.
- `test/update_service_test.dart`: see §11.

---

## 18. Porting risks / gotchas

1. **Settings data migration**: values live in Flutter's DataStore (`SharedPreferencesAsync`), colors as Long ARGB. Decide whether to migrate (same applicationId → in-place upgrade over the Flutter APK) or start fresh; same signing key `upload` required for in-place updates.
2. **Color source semantics**: two booleans encode a 3-state enum; Album Art and Custom share `default_theme_color`; `dynamic_theme_color` is effectively dead. Material You = single accent `system_accent1_600` re-seeded through tonal-spot, not the OS dynamic scheme.
3. **Accent palette is data-driven** (from album art HSL buckets + `#7C4DFF`), not a fixed list. Reproduce bucketing exactly (sat<0.15 / L<0.15 / L>0.85 → neutral; else 30° hue sectors) and Flutter's HSL conversion.
4. **Color extraction = mean of opaque pixels on a 32×32 decode**; Palette API gives different colors → visibly different themes. Also the per-song cache lives in the library JSON (`dominantColor`).
5. **Seed → scheme**: must use Material Color Utilities tonal-spot to match `ColorScheme.fromSeed`; theme switches on every track change (ensure cached/cheap recomposition).
6. **Danger Zone only clears prefs** (not files, not in-memory settings); message promises more. Decide intended behavior.
7. **Updater**: unauthenticated GitHub API (rate limits), checks on every home launch with no throttle, version compare ignores build numbers and pre-release suffixes, release body shown raw (contains auto-generated notes), "Cancel" doesn't abort download, needs REQUEST_INSTALL_PACKAGES + FileProvider; only 3 ABIs published (no x86) — native Kotlin app may ship a universal APK, making ABI selection moot; keep asset naming if old Flutter clients must still update to the Kotlin build (they match `arm64-v8a`/`armeabi-v7a`/`x86_64` substrings in asset names and compare semver tags `vX.Y.Z+N`).
8. **Changelog** fetched from `raw.githubusercontent.com/.../main/CHANGELOG.md` (branch name `main` hardcoded); keep the file path/branch or old clients break; no offline fallback.
9. **Locale**: `de` branch dead; several hardcoded English UI strings; ICU plurals and leading-space/`%`/`@`/quote escaping when converting to Android XML.
10. **build.gradle.kts requires key.properties** even for debug builds; CI release tag `v{version}+{commitCount}` and the `release:` commit trigger must be replicated for the Kotlin project (Gradle versionCode from commit count).
11. `keep_playing_on_close` is read straight from prefs in the audio service — in Kotlin, the MediaSessionService must read the same store in `onTaskRemoved`.
12. Immersive mode is persisted but toggled outside Settings (tap on art); sort settings also persist here but are edited elsewhere.
