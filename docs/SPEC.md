# Sonora KMP — Port Specification

Source of truth for porting Sonora (Flutter, `/Users/yurtemre/Code/antigravity/kind-salk`, read-only, v1.28.1) to
native Kotlin. Audits live in `docs/audit/` (`data.md`, `playback.md`, `ui.md`, `settings.md`, `strings.tsv`);
visual identity in `docs/DESIGN.md`. This spec states **what** to build and **how to verify it**; audit sections
(e.g. "audit/playback.md §8") hold the long-form details and must be read by the implementer of each feature.
Execution plan: `docs/TASKS.md`.

Conventions: `S/` = `shared/src/commonMain/kotlin/de/yurtemre/sonora/shared/`, `SA/` = `shared/src/androidMain/...shared/`,
`A/` = `androidApp/src/main/kotlin/de/yurtemre/sonora/`, `AR/` = `androidApp/src/main/res/`.
"Parity" = behave exactly like Flutter. "FIX" = intentional deviation listed in §9.
Lowercasing is always `lowercase(Locale.ROOT)`-equivalent (`lowercase()` in common code); string compare is plain
`String.compareTo` (UTF-16 code units), never `Collator` (audit/data.md conventions).

---

## 1. Overview & scope

| Item | Value |
|---|---|
| Platform | Android only (phone, tablet, foldable, Android Auto). No iOS/Windows/desktop targets. |
| SDK | minSdk 24, targetSdk 37, compileSdk 37, JDK toolchain 17 |
| applicationId | `de.yurtemre.sonorakmp` (release), `de.yurtemre.sonorakmp.dev` (debug, versionName suffix `-dev`, label "Sonora Dev") |
| namespace | app `de.yurtemre.sonora`, shared `de.yurtemre.sonora.shared` |
| Coexistence | Different applicationId → installs side by side with the Flutter app (`de.yurtemre.sonora`). |
| Version | `sonora.versionName` / `sonora.versionCode` in `gradle.properties` (CI overrides versionCode = `git rev-list --count HEAD`). |
| Data migration | **None in place.** Flutter prefs/files are not read. The migration path is: export a backup in the Flutter app (Settings › Backup) → import it in Sonora KMP. Backup format v1 (§5.5) must stay read- and write-compatible with Flutter 1.28.x. |
| Identity | New visual design "Afterglow" (DESIGN.md). Behaviour parity with Flutter unless listed in §9. |
| Out of scope | Streaming, online lyrics, tag editing, cloud sync, iOS file import, Windows. |

---

## 2. Architecture

### 2.1 Modules & packages

**`:shared`** (KMP, Android target only, package `de.yurtemre.sonora.shared`)

| Source set / package | Contents | Notes |
|---|---|---|
| commonMain `model/` | `Song`, `Playlist`, `AlbumGroup`, `ArtistGroup`, `ReplayGainMode`, `ReplayGainInfo`, `MfxPreset`, `EqPreset`, `EqMode`, `SongActivityView`, `RepeatMode`, `NowPlayingStyle`, `ThemeColorSource`, `ThemeMode`, `AppLocale`, sort enums, `AppSettings` data class | pure data, `@Serializable` where persisted |
| commonMain `library/` | `ArtistParser`, `AlbumGrouper`, `ArtistGrouper`, `LibrarySorter`, `LibrarySearchIndex`, `TitleFormatter`, `DurationFormat`, `ScrollSection`, `SeedColorBuckets` | pure, unit-tested |
| commonMain `queue/` | `QueueState` (immutable) + `QueueEntry(uid, songId)` + operations | pure; no ExoPlayer types |
| commonMain `audio/` | `ReplayGainReader` (ID3v2/FLAC/Ogg/Opus/MP4 over `ByteArray`/`ByteSource`), `ReplayGainMath`, `SpeedPresets`, `EqGains` (mode/BT patch/preset math), `FadeCurve`, `SleepTimerState` (pure reducer) | pure |
| commonMain `lyrics/` | `LyricsParser` (LRC + plain) → `Lyrics(lines, isSynchronized)` | pure |
| commonMain `playlist/` | `M3uCodec` (encode/decode, matcher) | pure |
| commonMain `stats/` | `ListenSessionTracker` (milestones), `StatsAggregates`, `ActivityView` | pure |
| commonMain `backup/` | `BackupV1` schema (kotlinx.serialization), `BackupCodec` (parse/validate/encode), `SongMatcher` (3-pass), `RestorePlanner` → `RestorePlan`, `BackupError` enum | pure |
| commonMain `update/` | `SemVer`, `GitHubReleasesClient` (Ktor), `Release`/`ReleaseAsset`, `ChecksumParser`, `UpdateChecker`, `UpdateCheckResult` sealed | Ktor engine injected (MockEngine in tests) |
| commonMain `settings/` | `SettingsKeys`, `SettingsRepository` (DataStore `Preferences`), `PlaybackStateStore` | all keys §5.3 |
| commonMain `db/` | Room KMP entities, DAOs, `SonoraDatabase` (`@ConstructedBy`) | bundled SQLite driver |
| commonMain `repo/` | `LibraryRepository`, `PlaylistRepository`, `FavoritesRepository`, `StatsRepository`, `BackupRepository` (+ interfaces for platform pieces: `LibraryScanner`, `FileStore`) | Flows out, suspend in |
| androidMain `scan/` | `MediaStoreScanner`, `DirectoryScanner`, `TagReader` (jaudiotagger + `MediaMetadataRetriever` fallback), `ArtworkCache`, `ArtistImageScanner`, `LyricsLocator`, `SeedColorExtractor` (Palette) | port of `MainActivity.queryMediaStore` |
| androidMain `platform/` | Room builder, DataStore factory, `AndroidFileStore`, folder-path ↔ tree-URI conversion | |
| androidMain `di/` | Koin `sharedModule` | |

**`:androidApp`** (package `de.yurtemre.sonora`)

| Package | Contents |
|---|---|
| `di/` | `AppModule` aggregating feature modules |
| `playback/` | `SonoraPlaybackService : MediaLibraryService`; `PlayerFactory` (ExoPlayer, audio attributes, wake mode); `SonoraForwardingPlayer` (fades, volume = user × fade × ReplayGain, repeat-one next/prev semantics, completed-state play); `QueueController` (shared `QueueState` ⇄ ExoPlayer window); `AudioEffects` (Equalizer on audio session id, speed/pitch); `AudioFocusController` (pause-on-duck); `NoisyAndReconnectHandler`; `SleepTimer` + `SleepTimerNotifier`; `AutoBrowseTree`; `ArtworkProvider` (ContentProvider for content:// art); `PlaybackConnection` (UI-side `MediaController` + StateFlows); `PositionTicker`; `ListenStatsRecorder` |
| `widget/` | `NowPlayingWidgetProvider` (RemoteViews, small + large), `WidgetStateStore`, `WidgetRenderer` |
| `update/` | `ApkDownloader` (progress, cancel), `Sha256Verifier`, `PackageInstallerHelper` (session install + result receiver), `InstallPermissionHelper`, `UpdateViewModel` |
| `ui/theme/` | `SonoraTheme` (Afterglow, MaterialKolor, AMOLED, color sources), `Type.kt`, `Shapes.kt`, `Motion.kt` |
| `ui/components/` | AlbumArt, ArtistAvatar, SongRow, PulseButton, EmberSeekBar, MiniPlayer, FastScroller, MarqueeText, EmptyState, ConfirmDialog, AfterglowBackdrop, Visualizer, Vinyl, MultiSelectBar, SectionHeader |
| `ui/navigation/` | `Destinations.kt` (`@Serializable` NavKeys), `SonoraNavHost` (Navigation 3 `NavDisplay`), `AdaptiveScaffold` (ShortNavigationBar / NavigationRail / WideNavigationRail), `NowPlayingOverlay` (expand/collapse + predictive back) |
| `ui/screens/<feature>/` | `<Feature>Screen.kt` + `<Feature>ViewModel.kt` + `<Feature>Module.kt` (Koin) per feature: `gate`, `onboarding`, `home`, `nowplaying`, `album`, `artist`, `playlist`, `favorites`, `recent`, `stats`, `settings`, `changelog`, `licenses`, `debug` |
| root | `MainActivity` (AppCompatActivity, single activity), `SonoraApplication` (Koin start, Coil ImageLoader) |

Rules:
- `:shared` never returns user-visible strings: it returns enums/sealed results/numbers; the app maps them to
  `R.string`/`R.plurals`.
- UI never talks to ExoPlayer directly: `PlaybackConnection` (MediaController + custom `SessionCommand`s) exposes
  `StateFlow<PlayerUiState>`; queue mutations are custom commands handled by `QueueController` in the service.
- One `SonoraDatabase`, one DataStore (`sonora_settings`), both singletons in Koin; the service and UI share them.

### 2.2 Libraries (from `gradle/libs.versions.toml`)

Media3 1.11.1 (exoplayer, session, common) · Room 2.8.5 KMP + `sqlite-bundled` 2.7.1 · DataStore 1.3.0 · Koin 4.2.2
(BOM) · Ktor 3.6.1 (OkHttp engine on Android, MockEngine in tests) · Coil 3.6.3 · kotlinx.serialization 1.12.0 ·
coroutines 1.11.0 · jaudiotagger 3.0.1 · MaterialKolor 5.0.1 (`com.materialkolor:material-kolor`, already in the
catalog and `androidApp` deps) · Navigation 3 1.2.1 + `lifecycle-viewmodel-navigation3` · Material3 **1.5.0-rc01**
(expressive APIs; 1.4.0 stable keeps them internal) · material3-adaptive 1.3.0 · Palette 1.1.0 · DocumentFile 1.1.0 ·
reorderable 3.1.0 · AppCompat 1.8.0 (per-app locales) · core-splashscreen.
To add (pin an exact version ≥ 2 weeks old): a Compose Markdown renderer
(`com.mikepenz:multiplatform-markdown-renderer-m3`) and AboutLibraries (`com.mikepenz.aboutlibraries` plugin + compose
UI) for the licenses screen. Tests: JUnit4, kotlin-test, Turbine, Robolectric 4.17, Compose ui-test, ktor-client-mock.

---

## 3. Flutter → Kotlin mapping

### 3.1 pubspec dependencies

| Flutter | Role | Replacement |
|---|---|---|
| just_audio 0.10.6 (+ platform_interface, windows) | player | Media3 ExoPlayer + `SonoraForwardingPlayer` |
| audio_service 0.18.19 (+ audio_service_win) | MediaSession, FGS, notification, browse | Media3 `MediaLibraryService` + `DefaultMediaNotificationProvider` + `MediaLibrarySession.Callback` |
| audio_session 0.2.x | focus, attributes, noisy | `AudioAttributes(USAGE_MEDIA, MUSIC)`, `handleAudioFocus`, `AudioFocusController`, `setHandleAudioBecomingNoisy` |
| audio_tags_lofty (Rust FFI) | tag read | jaudiotagger (+ `MediaMetadataRetriever` fallback) in `TagReader` |
| flutter_local_notifications 23 | sleep-timer notification | `NotificationCompat` + `NotificationChannel` in `SleepTimerNotifier` |
| file_picker 13 | folder pick, m3u/json/image pick, save backup | `ActivityResultContracts.OpenDocumentTree` / `OpenDocument` / `CreateDocument("application/json")` / `PickVisualMedia` |
| permission_handler 13 | runtime permissions | `ActivityResultContracts.RequestMultiplePermissions` + `ContextCompat.checkSelfPermission` |
| shared_preferences 2.5 | settings | DataStore Preferences (`SettingsRepository`) + Room for collections |
| path_provider | dirs | `Context.filesDir` / `cacheDir` |
| go_router 18 | routing | Navigation 3 back stack (§6) |
| animations 3 | shared axis | Compose `AnimatedContent` + `MotionScheme.expressive()`, `SharedTransitionLayout` |
| material_ui, cupertino_icons | widgets/icons | Compose Material3 1.5 expressive + material-icons-extended |
| flutter_localizations, intl | l10n, plurals, dates | `strings.xml`/`plurals`, `values-ja`, `java.text.DateFormat`/`android.text.format.DateFormat` |
| flutter_markdown_plus | changelog | Compose Markdown renderer (to add) |
| http 1.6 | GitHub API, APK download | Ktor (OkHttp engine) |
| flutter_launcher_icons | icons | existing `mipmap`/`drawable-*` launcher assets |
| fake_async, flutter_test, flutter_lints | tests/lint | kotlinx-coroutines-test (virtual time), JUnit/Robolectric/Compose test, Android lint |

### 3.2 MethodChannels & Dart services

| Channel / method | Replacement |
|---|---|
| `de.yurtemre.sonora/volume` `ensureMediaVolume` | `AudioManager` call in `SonoraApplication`/service start (PLAY-28) |
| … `getMediaVolume` | dropped (unused) |
| … `getAndroidSdk` | `Build.VERSION.SDK_INT` |
| … `getDeviceAbi` | dropped (universal APK, §7) |
| … `installApk` | `PackageInstallerHelper` session install (§7) |
| … `openUrl` | `Intent.ACTION_VIEW` helper (`UrlOpener`) |
| … `getPackageInfo` | `BuildConfig.VERSION_NAME/VERSION_CODE`, `isDevBuild = BuildConfig.DEBUG || APPLICATION_ID.endsWith(".dev")` |
| … `shareFiles` | `ShareHelper` (FileProvider `${applicationId}.fileprovider`, SEND / SEND_MULTIPLE, chooser) |
| … `stopForegroundService` | dropped (Media3 manages FGS) |
| `de.yurtemre.sonora/mediastore` `scanMediaStore` | `MediaStoreScanner` (androidMain) |
| … `scanFile` | dropped (no caller) |
| … `openFileFolder` | `FolderOpener` (reduced strategy list, §9 D-31) |
| … `getDynamicWallpaperColor` | `dynamicLight/DarkColorScheme` (System source) |
| … `cropAndResizeImage` | `PlaylistCoverProcessor` (512 px, JPEG q85, center crop) |
| `de.yurtemre.sonora/home_widget` `update` | direct call from service `Player.Listener` → `NowPlayingWidgetProvider.update(state)` |
| Dart `MusicScanner`, `scanner_*` | `LibraryRepository` + androidMain `scan/` |
| `LibrarySearchIndex` | `library/LibrarySearchIndex` |
| `BackupService` | `backup/` + `BackupRepository` |
| `StatsService` | `stats/` + `StatsRepository` |
| `ThumbnailStore`, `ImagePreflightService`, image cache caps | Coil 3 memory+disk cache (`size` hints 128/512/800 px), no custom thumbnail files |
| `ColorExtractor` | `SeedColorExtractor` (Palette, DESIGN §1) |
| `LyricsService` | `LyricsLocator` + `LyricsParser` |
| `ReplayGainReader` | `audio/ReplayGainReader` (1:1 port) |
| `PermissionService` | `PermissionsController` (gate + onboarding) |
| `UpdateService` | `update/` (§7) |
| `PositionTicker` | `PositionTicker` Flow |
| `SleepTimerNotificationService` | `SleepTimerNotifier` |
| `VolumeService`, `NativeBridge` | small Android helpers (above) |
| `SettingsProvider`, `ThemeProvider`, `PlayerProvider` | `SettingsRepository` + ViewModels + `PlaybackConnection` |

### 3.3 Native Android components

| Flutter Android component | Replacement |
|---|---|
| `MainActivity : AudioServiceActivity` (singleTop, edge-to-edge) | `MainActivity : AppCompatActivity`, `singleTop`, `enableEdgeToEdge()`, splash screen API |
| `com.ryanheise.audioservice.AudioService` (+ `MediaBrowserService` filter) | `SonoraPlaybackService` exported, filters `androidx.media3.session.MediaLibraryService` + `android.media.browse.MediaBrowserService`, `foregroundServiceType="mediaPlayback"` |
| `com.ryanheise.audioservice.MediaButtonReceiver` | `androidx.media3.session.MediaButtonReceiver` |
| `NowPlayingWidgetProvider` + `NowPlayingWidget.kt` + layouts/colors/dimens | `widget/` port (WID-*) |
| flutter_local_notifications boot/scheduled receivers | dropped (no scheduled notifications) |
| FileProvider `${applicationId}.fileprovider` (external/cache/files/root) | FileProvider with `cache-path` (updates, share) + `files-path`; no root-path |
| `automotive_app_desc.xml` + meta-data `com.google.android.gms.car.application` | same |
| `<queries>` for 17 file managers | reduced (§9 D-31) |
| `LaunchTheme`/`NormalTheme` | `Theme.Sonora` + `Theme.SplashScreen` |
| Permissions: READ_MEDIA_AUDIO, READ_MEDIA_IMAGES, READ_EXTERNAL_STORAGE, POST_NOTIFICATIONS, FOREGROUND_SERVICE(_MEDIA_PLAYBACK), WAKE_LOCK, INTERNET, REQUEST_INSTALL_PACKAGES | same; `READ_EXTERNAL_STORAGE maxSdkVersion=32`; no cleartext traffic |

---

## 4. Features & acceptance criteria

Format: **ID — feature**: behaviour (constants) → ✅ acceptance. "audit/x §n" = mandatory detail reference.

### 4.1 Library (LIB)

| ID | Feature & behaviour | Acceptance |
|---|---|---|
| LIB-01 | **Folder selection.** `OpenDocumentTree`; persist URI permission; convert tree URI to filesystem path (`primary:Music` → `/storage/emulated/0/Music`, `<uuid>:x` → `/storage/<uuid>/x`); store `scan_folder_path` + `scan_folder_uri`. `null` = not configured. | Picking Music folder stores `/storage/emulated/0/Music`; permission survives reboot. |
| LIB-02 | **Permissions.** API ≥ 33: `READ_MEDIA_AUDIO` (required) + `READ_MEDIA_IMAGES` (best-effort); < 33: `READ_EXTERNAL_STORAGE`; then `POST_NOTIFICATIONS` (33+, best-effort). Result = audio granted. audit/data §2.1. | Denying images still allows scanning; denying audio routes to permission gate. |
| LIB-03 | **MediaStore scan.** Query/selection/variants/escaping/fallback titles exactly as audit/data §2.3 (`IS_MUSIC != 0`, `/sdcard`⇄`/storage/emulated/0`, case-insensitive prefix recheck, `Unknown Artist`/`Unknown Album`, `<unknown>`). `Song.id = _ID`. MediaStore leaves `discNumber/genre/bitrate/sampleRate` null, `trackNumber` raw (disc×1000+track). | Instrumented/robolectric fake cursor → expected `Song` list; LIKE escaping of `%`/`_` in folder names. |
| LIB-04 | **Incremental sync.** Unchanged rule (path match ∧ mtime ∧ size ∧ (art null ∨ same art)) keeps cached row; else re-create carrying favorites/seed color by path; dropped files removed. Missing folder → return cache untouched. Empty MediaStore → directory scan. Any exception → cache returned (FIX D-19). Writes `last_sync_timestamp`, `last_sync_duration_ms`; clears `postpone_sync_until` on every successful sync. Cache = Room `songs` (transaction). | Unit test with fake scanner: unchanged song keeps object fields; unmounted folder keeps rows; exception keeps rows. |
| LIB-05 | **Album art cache.** `cacheDir/album_art/art_album_<albumId>.jpg`, `.none` marker, `.tmp` + rename; API ≥ 29 `loadThumbnail(720×720)` JPEG q90, else albumart URI copy; FNF/empty → marker; other error → retry; cold cache: pool `clamp(cores,1,4)`, ≤ 5 min; warm: background extraction, **rows patched in DB when done** (FIX D-20); prune `^art_album_(\d+)\.(jpg|none|jpg\.tmp)$` not in result. audit/data §2.3. | First scan yields art paths; second scan of album without art does not retry (marker); art appears without second sync. |
| LIB-06 | **Directory scan fallback.** Walk via `DocumentFile` tree (or `File` when readable); audio extensions `mp3 m4a mp4 aac flac ogg opus wav wma amr 3gp ts mkv mid midi`; `cover.{jpg,png,webp}` per dir; tags via `TagReader` (title, artist→albumArtist fallback, album, track, disc, genre, year, duration, bitrate, sample rate, format, embedded picture); fallbacks & track-from-filename regex `^(\d+)\s*[-_.]?\s*` per audit/data §2.4. Ids: existing by path else `max+1`. Embedded art → `filesDir/embedded_art/<songId>.jpg` (overwritten; orphans deleted) (FIX D-21). | Fixture tree: `<root>/Some Artist/Album/Nested Song.mp3` → artist "Some Artist"; `<root>/Flat Song.mp3` → Unknown Artist. |
| LIB-07 | **Artist images.** Single algorithm: walk from song dir up to scan root; per individual artist (LIB-09) match `artist.{jpg,png,webp,jpeg}` then `<artistLower>.{…}`, else dir name equals artist (case-insensitive) → first image; no "any image" fallback. Stored in Room `artist_images` only (FIX D-22). Used iff `prefer_local_artist_images`. | Folder `Queen/artist.jpg` → Queen avatar; album `cover.jpg` never becomes artist image. |
| LIB-08 | **ReplayGain fill.** Songs with `rgScanned=false` read on `Dispatchers.IO` fan-out 4; IO failure → stay unscanned (retry next sync); success/no tags → scanned. Reader: audit/data §9.1 (1:1 port, never throws, 16 KiB block cache, R128 Opus rule, gain range ±60 dB, peak (0,10]). | Reader unit tests for ID3v2.3/2.4 TXXX, FLAC, Ogg Vorbis, Opus R128 (`raw/256+5`), MP4 `----`. |
| LIB-09 | **Artist parsing.** Regex `[,;/&\\]|\s+(?:feat\.?|ft\.?|featuring|vs\.?|with|[xX\+])\s+` (case-insensitive), bracket strip, case-insensitive dedupe keeping first, empty → `["Unknown Artist"]`. Parity incl. `AC/DC` → `[AC, DC]`. audit/data §5.1. | `"Drake feat. Future & Young Thug"` → 3 names; `""` → Unknown Artist. |
| LIB-10 | **Album grouping.** Bucket by trimmed lowercase album name; unknown bucket rule; common-artist intersection → majority (> n/2) → split by primary; in-group order disc(null→1), track(null→0), both-zero → path, else titleLower; groups by nameLower stable. audit/data §5.2. | "808" Ufo361 test and "Greatest Hits" Queen/GnR test pass. |
| LIB-11 | **Artist grouping.** One group per individual artist (lower key, first spelling), songs & albums membership, `localImagePath`. audit/data §5.3. | Song "A & B" appears under A and B. |
| LIB-12 | **Sorting.** Songs `title/artist/duration/recent/plays` (+id tie-break, descending negates whole compare incl. tie-breaks; `plays` = activity view); albums `name/artist/tracks/recent`; artists `name/albums/songs` (**`songs` implemented**, FIX D-23); playlists `name/songs` (existing ids, tie → name); favorites `name/duration/date`. Selecting `plays` forces descending; leaving it → ascending. audit/data §5.4. | Table-driven sorter tests per option & direction. |
| LIB-13 | **Search.** Songs: all whitespace terms of `query.trim().lowercase()` substring-match title/artist/album (AND), then sorted; albums: whole query in name/artist; artists/playlists: in name. No folding. Shared query across Home tabs. audit/data §6. | `"eagles calif"` → Hotel California; `"opera"` matches album; non-match → empty. |
| LIB-14 | **Display title.** `filter_title_features` removes bracketed feat; `filter_title_artist` removes `— – -` suffix then prefix artist (case-sensitive, escaped); empty → fallback. Used in UI, notification, Auto; raw title for search/sort/M3U. audit/data §10.1. | `"Cosmos - Starlight (feat. Stellar)"`/`Cosmos`, both on → `Starlight`. |
| LIB-15 | **Durations.** Song `m:ss` (minutes unbounded, parity); total `"Xd Yh Zm Ws"` rule (audit/data §10.2) returned as `DurationParts` and rendered with localized unit strings; seek bar `h:mm:ss` when ≥ 1 h (FIX D-05). | `3600 s` → parts (h=1,s=0, minutes omitted); 90 s → `1m 30s`. |
| LIB-16 | **Playlists.** Create (id = epoch-ms string, bumped +1 while colliding — FIX D-24), delete, rename, description (null clears), cover set/remove (old file deleted), add songs (dedupe, append), remove songs (all occurrences), reorder (optimistic), save queue as playlist (no dedupe). Ids not in library are kept in storage, hidden in UI. Legacy built-in id `favorites` never created. audit/data §4.1. | DAO tests: add dedupes, remove removes duplicates, reorder persists order. |
| LIB-17 | **Playlist cover.** `PickVisualMedia` → center-crop square, downscale to ≤ 512 px, JPEG q85 → `filesDir/playlist_covers/<id>.jpg`; Coil cache key invalidated. | Non-square 2000×1000 image → 512×512 file. |
| LIB-18 | **M3U export.** `#EXTM3U` + `#EXTINF:<floor sec>,<artist> - <raw title>` + absolute path, `\n`, UTF-8; missing songs skipped; file `cacheDir/share/<sanitized name>.m3u` (`[\\/:*?"<>|]`→`_`) → share sheet with text `exported_playlist`. Failure → `failed_to_export`. | Encoded output equals fixture; name `a/b` → `a_b.m3u`. |
| LIB-19 | **M3U import.** `.m3u`/`.m3u8` only; skip blank/`#`; match: exact path → else case-insensitive equal file name (URL-decoded) → first in library order (FIX D-25: no suffix match); duplicates kept; name = file name minus `\.m3u8?$`; description = string resource `imported_from_m3u` stored as text at creation; zero matches → nothing + snackbar `import_m3u_no_matches` (FIX). | `a.mp3` no longer matches `ba.mp3`. |
| LIB-20 | **Favorites.** Songs: Room `favorite_songs(songId, filePath, favoritedAtMs)`; toggle sets now / removes; bulk set uses one `now`. On resync, favorites whose id vanished are re-linked by `filePath` (FIX D-26). Albums: key `"<nameLower>|||<artistLower>"`; artists: `nameLower`; both with ms date. | Rescan that changes MediaStore id keeps favorite via path. |
| LIB-21 | **Sync reminder.** Show banner iff library non-empty ∧ `last_sync_timestamp` set ∧ `< now − 30 d` ∧ `now ≥ postpone_sync_until`; computed at startup; postpone = now + 30 d. | Clock-injected test of all 4 conditions. |
| LIB-22 | **Seed colors.** Per-song seed from artwork via Palette (vibrant → dominant), cached in `songs.seed_color`; failures → `colorless_artwork_paths`; background pass 3 s after library load, 100 ms between images, batch DB write. Custom accent swatches: Ember first, then album-art seeds bucketed (sat < .15 ∨ L < .15 ∨ L > .85 → neutral, else 30° hue sector; first per bucket). audit/settings §9.3–9.4. | Red PNG → red-ish seed; transparent PNG → colorless set; bucket function unit-tested. |
| LIB-23 | **Library refresh while playing.** Queue entries keep order; song objects swapped by `filePath`; current item metadata & ReplayGain refreshed. Nothing playing + first load → PLAY-24 restore. | Rescan during playback does not interrupt audio; changed gain applied. |
| LIB-24 | **Lyrics.** Sidecar `<path-no-ext>.lrc` else `.txt` (exact case). LRC regex `\[(\d+):(\d+)(?:\.(\d+))?\]`; fraction = decimal fraction (`.5`=500 ms, `.05`=50 ms, `.123`=123 ms — FIX D-27); `[offset:±ms]` applied (FIX); multi-stamp lines duplicated; empty timed text dropped; sorted stable. Plain: non-empty lines minus `^\[(ti|ar|al|by|offset|re|ve|length):`. audit/data §8. | Parser tests: synced, multi-stamp, plain with `[Verse 1]`, offset. |

### 4.2 Playback (PLAY)

| ID | Feature & behaviour | Acceptance |
|---|---|---|
| PLAY-01 | **Service & items.** `SonoraPlaybackService : MediaLibraryService`; MediaItem: `mediaId = "q:<uid>"` for queue items (`song:<id>` for browse), URI `file://` path (content URI `MediaStore.Audio.Media` by id preferred when id from MediaStore), metadata displayTitle/artist/album/duration, `artworkUri` = `content://${appId}.artwork/song/<id>` (AUTO-02), extras `songId`, ReplayGain (`rg_*`). | Notification, lock screen and Auto show title/art. |
| PLAY-02 | **Load & play.** `playSong(song, list, playlistId?)`: request token, queue = copy, start at first index with that id (else 0), position 0, faded play; stale requests abort silently; `playlistId` stored for stats. | Rapid taps on 3 songs → only last plays. |
| PLAY-03 | **Shuffle.** On: history + current unchanged, upcoming shuffled (Fisher–Yates, injectable `Random`), no interruption, stat `shuffleSessionStarts++`. Off: restore original order at current song (by entry uid), **live position**, no audible reload when possible (FIX D-11). `quickShuffle(list)`: shuffled copy, original = list, shuffled=true, stat++. Original-queue bookkeeping: add → append both; play-next → after current in original; remove → that entry (FIX D-03); reorder → original unchanged (parity). audit/playback §2.4. | QueueState tests: history preserved, unshuffle returns to original order with current kept. |
| PLAY-04 | **Repeat.** Cycle off → all → one → off; not persisted (parity). ONE: next/previous restart current (seek 0) via ForwardingPlayer. ALL: wraps to logical index 0 (FIX D-12). OFF at end: completed; play/pause on completed restarts at index 0. | Unit test on ForwardingPlayer/QueueState rules. |
| PLAY-05 | **Play next / add to queue.** Insert at current+1 (LIFO stacking for repeated play-next, parity); add → append; duplicates allowed; batch variants with snackbar counts. | Two play-next calls → second is directly after current. |
| PLAY-06 | **Remove / reorder.** Remove by entry uid; removing current advances to following item; index bookkeeping per audit/playback §2.7; reorder uses `moveMediaItem` (no restart, FIX D-13); window reload only if outside window and never restarts current. | Remove current while playing → next starts; reorder doesn't reset position. |
| PLAY-07 | **Skip/seek.** Next: skip stat if not last; last item + repeat off → seek 0 (parity). Previous: position **> 3 s** → seek 0 (+ restart stat) else previous item. Seek < 3 s from > 3 s → restart stat. `skipToQueueItem(i)` keeps play state. Controller-originated (notification/BT/Auto/widget) skips record **no** stats (parity). | Stats recorder tests. |
| PLAY-08 | **Window & gapless.** `QueueController` keeps ExoPlayer timeline = window `[start-10, start+30)`; slide forward +20 when < 10 after, backward +15 when < 5 before; never trims; edits mirrored; repeat-all appends from logical start when window reaches logical end (gapless wrap). ReplayGain applied on `onMediaItemTransition` before metadata broadcast; decode error → pause (parity `maxSkipsOnError=0`). audit/playback §2.2, §2.9. | 10 000-item queue loads < 1 s; transition across slide boundary is gapless and updates current item + gain. |
| PLAY-09 | **Transport fades.** 400 ms, 20 linear steps (20 ms) from current volume; play: volume 0 → restore; pause: ramp to 0 → pause → restore; token-cancelled reversal; restore ≤ 0.05 → 1.0 quirk (parity). Immediate (no fade): noisy, sleep-timer song boundary, focus loss, stop/skip/seek. All entry points (UI, notification, BT, widget, Auto) go through `SonoraForwardingPlayer.play/pause`. audit/playback §4. | Virtual-time test of ramp values and reversal. |
| PLAY-10 | **ReplayGain volume.** Mode off/track/album (`replay_gain_mode`); gain fallback album⇄track; peak = trackPeak ?: albumPeak; `m = 10^(g/20)`, clip guard `m*peak>1 → 1/peak`; player volume = `clamp(user × fade × m, 0, 1)`; no preamp. audit/data §9.2. | −6 dB → ×0.501; vol 0.5 → 0.2506; off → unchanged. |
| PLAY-11 | **User volume.** In-app gain 0..1 rounded to 2 decimals, persisted `volume` (1.0); NP volume chip shows %. | Persisted across restart. |
| PLAY-12 | **Custom speed.** Clamp [0.5, 2.0], slider stops `0.5,1.0,1.25,1.5,2.0`, pitch 1.0, persisted `speed`; disabled while preset active. | |
| PLAY-13 | **MFX presets.** 14 presets (audit/playback §5.2 table) speed = pitch via `PlaybackParameters`; `normal` = none; re-applied per track; effective duration `round(ms/speed)`. Persist `mfx_preset_name`. | Nightcore → speed & pitch 1.30. |
| PLAY-14 | **EQ modes.** `Equalizer(0, audioSessionId)` recreated on session-id change; precedence lofi > warmth > bass_boost > custom > off; lofi/warmth/bass mutually exclusive; gains per mode & BT patch exactly audit/playback §5.3 table (relative to device min/max mB); errors swallowed (EQ unsupported → controls disabled). | `EqGains` tests: ±15 dB → bass 12/6, warmth ±7.5 vs +4.5/−3.75, lofi −15 vs −8.25. |
| PLAY-15 | **Custom EQ.** Switch on → apply current gains; **off → only disables custom EQ** (FIX D-02); presets Flat/Pop/Rock/Jazz/Classical 5 fractions mapped `floor(i/N*5).clamp(0,4)`, gain = `f≥0 ? f*max : −f*min`; vertical sliders commit on drag end; labels `"{f/1000}k"`/`"{f}"`. Persist `mfx_isCustomEqEnabled`, `mfx_customEqGains`. | Preset mapping test for N=5 and N=10. |
| PLAY-16 | **Reset all effects.** Preset none, toggles off, custom EQ off (gains kept), speed 1.0. | |
| PLAY-17 | **Audio focus.** `pause_on_duck=false` → Media3 focus handling (system ducks). `true` → `AudioFocusController` with `willPauseWhenDucked`: pause immediately on transient loss/duck, resume on gain if it paused; permanent loss → pause. Toggle applies immediately. | Robolectric `ShadowAudioManager` focus change test. |
| PLAY-18 | **Becoming noisy.** Honour `pause_on_disconnect` (default true): pause immediately and set `wasPlayingBeforeDisconnect`; if false keep playing (FIX D-06). | |
| PLAY-19 | **Resume on connect.** `resume_on_connect` (false): `AudioDeviceCallback.onAudioDevicesAdded` with any output not builtin speaker/earpiece ∧ flag set ∧ not playing → faded play, clear flag. | |
| PLAY-20 | **Task removed.** `keep_playing_on_close=false` → stop player & service, **keep** last-played state; true → keep playing. | Swipe app from recents with setting off → playback stops; next launch restores. |
| PLAY-21 | **Close player.** Notification close button, notification dismiss, mini-player swipe-down: stop, clear queue + original + playlist context, clear `last_played_*`, widget → nothing playing. Mini-player swipe-down offers Undo snackbar (4 s) restoring queue/index/position paused (FIX D-07). | |
| PLAY-22 | **Media notification.** Channel `playback` ("Playback"), small icon monochrome; buttons prev/play-pause/next; custom buttons (media button preferences): timer inactive → `close_player`; timer active → `+1 min`, `End timer` (FIX D-08 ordering); seek bar; tap opens app (NP expanded). Media3 default foreground behaviour (leaves FGS on pause; dismissible when paused → PLAY-21) (D-09). | |
| PLAY-23 | **Media buttons.** PLAY_PAUSE/HEADSETHOOK toggle (faded), NEXT/PREVIOUS (3 s rule), PLAY, PAUSE, STOP, FF/REW ±10 s. Media3 default multi-tap on HEADSETHOOK accepted (D-10). | |
| PLAY-24 | **Persist & restore.** Save `last_played_song_id`, queue ids, original ids, position, shuffled on item change, play/pause change, `ON_STOP` of process lifecycle, and every 15 s while playing (FIX). Restore once on first library load if `restore_last_played_song` ∧ queue empty ∧ id exists: rebuild queues (fallback `[song]`), index of id (else 0), seek saved position, **paused**. audit/data §3.4. | Kill process mid-song → relaunch shows same song at ±15 s, paused. |
| PLAY-25 | **Position ticker.** 200 ms while playing ∧ UI started ∧ subscribed; immediate emit on subscribe/resume; drop backward moves < 1500 ms unless after discontinuity; dedupe equal values. audit/playback §7.1. | Turbine tests for the 6 pinned behaviours. |
| PLAY-26 | **Sleep timer logic.** `SleepTimerState` reducer; 1 s ticks; fade over last `fade` seconds (`orig × rem/fade`), finish-song mode (250 ms poll, pause at song end/id change/user pause; fade by song remaining), finalize = faded pause + restore user volume, queue kept; stop restores pre-fade user volume; extend = `remaining + d` (start new if none); ticks don't recompose whole player state. audit/playback §8.3. | Port pinned tests: extend 5+2=7, restore 0.8 not mid-fade, new timer during stop survives, finish-song no countdown fade. |
| PLAY-27 | **Sleep timer notification.** Channel `sleep_timer` (LOW), id 8888, ongoing, onlyAlertOnce, monochrome icon; title `sleep_timer`, chronometer countdown (`setUsesChronometer`+`setChronometerCountDown` on API 24+) or `sleep_timer_finishing_song`; actions `+1 min`, `End timer` (service PendingIntents, do not open app — D-14). | |
| PLAY-28 | **Ensure media volume.** On service create: STREAM_MUSIC 0 → `max(max/3, 1)`, no UI. | |
| PLAY-29 | **Visualizer source.** Decorative only (no RECORD_AUDIO): amplitude 0/1 from isPlaying. | |

### 4.3 UI (UI)

| ID | Feature & behaviour | Acceptance |
|---|---|---|
| UI-01 | **Startup gate.** States Loading → (Onboarding | Permission | Error | Ready). Loading: `LoadingIndicator`; after 15 s show `gate_loading_slow`; 30 s → Error. Error: `gate_error_*` texts, actions Close app (`finishAndRemoveTask`) / Clear data & retry (DataStore + DB wipe, rerun). Permission: `gate_permission_*`, Grant → re-request; permanently denied → open app settings (FIX). Onboarding if `!onboarding_completed` or no folder. audit/ui §0. | Robolectric: fake repo hanging > 30 s → Error screen. |
| UI-02 | **Onboarding.** 3 non-swipeable pages, wordmark, 3 dots, FAB. (1) name field prefilled "User" + description; (2) permissions card with 2 status rows, `configure_permissions` → request → auto-advance when granted; FAB disabled until audio ∧ notifications granted (notifications auto-true < 33); (3) folder picker, `get_started` enabled when folder set. Finish: save name (blank → "User"), `use_greeting_title=true`, `onboarding_completed=true`, save folder, sync, snackbar `folder_configured_imported(n)` / `folder_configured`. audit/ui §10. | UI test: FAB disabled before permissions. |
| UI-03 | **Shell.** `AdaptiveScaffold`: compact `ShortNavigationBar` with 4 library tabs (Songs, Albums, Artists, Playlists); medium `NavigationRail`; expanded `WideNavigationRail` + list-detail. Initial tab = `default_start_page`. Mini player floats 8 dp above bar on every non-gate destination iff current song. | |
| UI-04 | **Home header.** Large top app bar; title greeting (`<12` morning, `<17` afternoon, else evening) with name if `use_greeting_title`, else app name; DEV badge if dev build; actions Recently played, Favorites, Settings (tooltips); 2 dp indeterminate progress while syncing; reselecting active tab scrolls to top. | |
| UI-05 | **Search/filter header.** Pill search field with clear; buttons hidden while focused; per tab: Songs shuffle (filtered); Albums/Artists shuffle all songs of filtered groups; Playlists Import M3U + Create; sort button (highlighted when songs sort = plays); count label (`song_count` + " • " + total duration; album/artist/playlist counts). | |
| UI-06 | **Sort sheet.** Per tab options & direction segments per audit/ui §2.3 (plays → most/least played segments); applies immediately, persists, haptic. | |
| UI-07 | **Fast scroller.** Draggable thumb, fades after 1.5 s idle, section bubble per `ScrollSection` (letter A–Z else `#`; artist letter when sorted by artist; `"{m}m"` duration; play count for plays); haptic on section change. | |
| UI-08 | **Songs tab.** Empty library states (no folder: `set_music_directory` + `choose_folder_subtitle`; folder: `no_music_files_found` + `no_music_files_in_folder_subtitle(folder)`; button configure/change); sync banner (UI-12); no-results state; tap plays filtered list; long-press multi-select; current song highlighted; `listens_count` metadata when sort = plays. | |
| UI-09 | **Albums tab.** Grid 2 cols compact / 3–4 medium+; card art (first song), name, artist, `track_count`; tap → Album; empty/no-match states. | |
| UI-10 | **Artists tab.** Row avatar (local image → first art → placeholder), name, "`album_count` • `song_count`", tap → Artist; empty/no-match. | |
| UI-11 | **Playlists tab.** Empty state with create; rows cover (custom → first song art → generated placeholder), name, `song_count` (existing only); menu: Export M3U, Edit description, Change cover (hint snackbar `choose_square_image`), Remove cover (if any), Rename, Delete (confirm → snackbar `playlist_deleted`). | |
| UI-12 | **Sync banner.** Songs tab only; `sync_library_database` + subtitle; buttons `remind_next_month` (postpone, snackbar `sync_postponed`) and `sync_now` (hide, progress bar, snackbar `synced_x_songs` + `sync_duration_ms`); disabled while syncing. | Port of `sync_prompt_banner_test` (3 cases). |
| UI-13 | **Multi-select.** Long-press enters (haptic); tap toggles; overlay check on art; exit via close/back/action/tab change; bar: close, `selected_count`, select/deselect all; actions Play next, Add to queue, Add to playlist, Favorite/Unfavorite (all-fav → unfavorite), Share (`share_songs_text(n)`), Remove from playlist (playlist detail only); snackbars `batch_*` 2 s. Available on Songs tab, Album, Artist, Playlist, Favorites, Recently played. | Port of `batch_actions_test`. |
| UI-14 | **Song row & menu.** Art 48 dp, displayTitle, artist (• metadata), duration, track chip / playing bars (DESIGN §5.5); menu: Play next, Add to queue, Add to playlist, Remove from playlist (if in playlist), Song info, Favorite toggle (live state). Hidden menu in Related, Up Next, Stats. | |
| UI-15 | **Mini player.** Pill card, progress hairline, art, title, artist, Pulse play/pause (replay icon when completed), next; tap/swipe-up → expand NP; swipe-down → close + Undo (PLAY-21); finger left → next, right → previous (velocity > 100 dp/s) (FIX D-04 naming); shared element to NP. | |
| UI-16 | **Now Playing chrome.** One overlay (§6); top bar: collapse (player view) / back (sub-views); title per view; Up Next: save-as-playlist action; menu: Song info. Background: Afterglow backdrop (animated gradient from 3 palette swatches) or real blur (API 31+) when `blurred_backgrounds`; scrim per DESIGN §1. | |
| UI-17 | **Player view.** Artwork area: tap toggles persisted `immersive_mode`; horizontal swipe next/prev; styles `modern` (≈80 % width ≤ 300 dp, immersive full), `vinyl` (rotates 1 turn/12 s while playing; square when lyrics overlay), `minimalist` (55 % ≤ 200 dp, ignores immersive); in-art lyrics overlay. Marquee title (3 s delay, 30 dp/s, 3 s pause), artist (multi → artist picker sheet) • album links (§6 collapse+push), favorite heart w/ pop. Chips: Lyrics (if has lyrics), Playlists, Timer (`stop_in_x(m:ss)` / finishing), MFX (active indicator), Volume %. Seek bar, optional volume slider (100 steps), controls ButtonGroup: shuffle, prev, Pulse, next, repeat (tooltips). Tabs Up Next / Lyrics / Related (disabled + tooltip when n/a; tapping active returns to player). | |
| UI-18 | **Up Next.** Header `queue_n_of_m` + scroll-to-current; list starts at current−1; drag handle reorder; absolute 1-based number (tabular); played rows 35 % alpha; tap non-current → skipToQueueItem; swipe end→start removes **that entry** + snackbar `removed_from_queue` with Undo (FIX D-03); empty `queue_empty`. | |
| UI-19 | **Lyrics.** Loading; none → `no_lyrics_found` + `place_lrc_file`; plain: centered scroll; synced: active = last line ≤ position, 64 dp rows, active emphasized (Seaglass), others 40 %, max 2 lines, auto-scroll 300 ms unless user scrolled < 4 s ago, tap line → seek + resume auto-scroll. Shared by tab & overlay. | |
| UI-20 | **Seek bar.** Ember wave (`LinearWavyProgressIndicator`, amplitude 0 paused); tap seeks; drag previews then seeks on release; elapsed / `-remaining`, tabular, `h:mm:ss` ≥ 1 h. | |
| UI-21 | **Related.** `from_this_album` (same album ∧ artist), `more_by_artist(artist)` (same artist, other album); enabled iff either list non-empty (FIX D-15); tap → player view + play with queue = related list (FIX D-16). | |
| UI-22 | **Visualizer.** If `show_visualizer`: 20 bars, 80 dp, pinned bottom, wave period 1500 ms, amplitude 450 ms ease; bar formula audit/playback §7.3; stops when reduce-motion. | |
| UI-23 | **Album detail.** Header art, name, artist, `track_count` • total duration; Play, Shuffle, favorite-album heart; tracks (full menu, multi-select). Missing → `album_not_found` state. | |
| UI-24 | **Artist detail.** Avatar (Cookie9Sided), name, albums • songs • duration; Play, Shuffle, favorite-artist; Albums carousel (→ Album route), Songs list. | |
| UI-25 | **Playlist detail.** Header cover, name, description (tap → edit; empty → `your_own_playlist`), count • duration; menu same as UI-11 (Delete: confirm → pop → delete → snackbar); Play/Shuffle with playlist context; reorderable list (`sh.calvin.reorderable`) with handles; swipe remove + `removed_from_playlist` snackbar + Undo; empty state. | Port: handle count == song count. |
| UI-26 | **Favorites.** Sections Artists (carousel), Albums (carousel auto-scrolls 1 dp/50 ms, loops, stops forever on user scroll; disabled with reduce-motion), Songs (count • duration); sort sheet: name/duration/date radio (closes sheet) + ascending switch (default date desc); empty `no_favorites_yet`. | |
| UI-27 | **Recently played.** Shuffle action; `song_count`; groups `today`/`yesterday`/`this_week`/localized "MMMM yyyy"; rows with `listens_count`; tap plays recent list; empty state + `all_songs` → back. | |
| UI-28 | **Song info sheet.** Groups metadata (tap copies, snackbar `copied_to_clipboard`), file (selectable path, Open folder, Copy path, Share file, size B/KB/MB/GB, modified, created (if differs), format), audio properties shown iff any row present (FIX D-17): bitrate, sample rate, RG track/album gain `+x.xx dB`. | Port of song-info test. |
| UI-29 | **Playlist selector.** Sheet; header + create (creates and adds); single mode: rows containing song tinted with check, tap toggles + snackbar `added_to_playlist_x` / `removed_from_playlist_x`; multi mode: tap adds, closes, `batch_added_to_playlist`; empty `no_playlists_created_yet`. | |
| UI-30 | **Dialogs.** Rename (prefill; blank → no-op), Edit description (blank → null), Create playlist, Save queue (snackbar `saved_playlist`), Confirm destructive (returns Boolean). | Port of dialogs tests. |
| UI-31 | **Sleep timer sheet.** Inactive: big `sleep_timer_minutes(n)`, slider 1–60 (initial default clamped), chips 5/10/15/20/30/45/60 (default emphasized), finish-song switch, Start; active: countdown h:mm:ss / m:ss or finishing, Cancel, +1 min. | |
| UI-32 | **MFX sheet.** "Experimental" badge; duration chip `m:ss (orig.) → m:ss (1.3x)` via resources; preset carousel (auto-center selected, 300 ms); switches Warmth, Lo-Fi, Bass boost, BT patch; custom speed; custom EQ section (hidden if EQ unsupported); Reset all. | |
| UI-33 | **Theme.** `SonoraTheme`: Afterglow schemes (MaterialKolor Expressive, pinned keys), sources Afterglow (default) / System (API 31+) / Album art (current seed, animated 450 ms) / Custom; theme mode system/light/dark; AMOLED pitch black; fonts Bricolage/Figtree; shapes & motion per DESIGN. | `ContrastTest` (A11Y-06). |
| UI-34 | **Adaptive layouts.** Compact/medium/expanded per DESIGN §6; expanded: library list left, detail or Now Playing right; settings max width 840 dp; NP two-pane (art left, controls/tabs right) on landscape/expanded. | Screenshot/robolectric at 3 widths render without crash. |
| UI-35 | **Messages.** Single `SnackbarHostState` in shell; floating, above mini player; 2 s default, 6 s for backup results. | |
| UI-36 | **Changelog.** Renders bundled `assets/CHANGELOG.md` (markdown, h1/h2/h3 styles) — works offline (FIX D-28); no network. | |
| UI-37 | **Licenses.** AboutLibraries list + fonts (OFL) from `docs/licenses`. | |
| UI-38 | **Keyboard.** Tap outside text field clears focus; IME actions done/search. | |

### 4.4 Settings (SET)

| ID | Feature & behaviour | Acceptance |
|---|---|---|
| SET-01 | **Settings root.** Sections: Library sync card (folder or `set_music_directory`, `active_sync_location`, Change/Set button → LIB-01 → sync, `sync_explanation`, last sync (localized date-time from `last_sync_timestamp` or `never_synced`) + duration, Sync now (spinner)); Stats → Statistics; App language (subtitle current; dialog System / English / 日本語); tiles Appearance, Library formatting, Playback, Privacy, Backup, Info; debug builds: Developer tools → Debug caches. audit/settings §2. | |
| SET-02 | **Appearance.** Theme mode segmented; Color source segmented (Afterglow / System (API ≥ 31 only) / Album art / Custom) with description cards, fallback/custom accent swatch row (pie of primary/secondary/tertiary, 56 dp); AMOLED switch; Blurred backgrounds; Now Playing style dropdown; Prefer local artist images; Show visualizer; Greeting switch + name dialog (blank → "User"). audit/settings §3. | Persist each; source switch recolors app. |
| SET-03 | **Library formatting.** Preview card (original struck-through `"Awesome Artist - The Greatest Hit (feat. Someone)"` / displayed result live); switches features/artist. | |
| SET-04 | **Playback.** keep playing on close, remember last song, Volume levelling (off/track/album), pause on notifications (duck), pause on disconnect, resume on connect, BT patch; default sleep timer (5,10,15,20,25,30,45,60), fade-out (Off,10,30,60 s), finish song, default start page (Songs/Albums/Artists/Playlists). Changes apply to running service immediately. audit/settings §5. | |
| SET-05 | **Privacy & permissions.** Static cards (data, storage, notifications, wake lock, internet + install-updates sentence, delete data → danger zone) matching the manifest. | |
| SET-06 | **Backup screen.** Export (CreateDocument `sonora-backup-YYYY-MM-DD.json`), Import (OpenDocument json) → confirm (`backup_restore_confirm_content(date)`) → restore → summary snackbar joined " · "; progress bar while busy; error mapping §BAK-06. | |
| SET-07 | **Info & support.** About (version `VERSION_NAME (VERSION_CODE)`, DEV badge, About dialog with app icon, description, made-with-love, "Kotlin • Material 3 Expressive"); Check for updates (UPD-07); Auto-check switch; Include pre-releases switch (UPD-03); Changelog; Licenses; thank-you card with heartbeat (1400 ms, 1.0→1.15→1.02→1.10→1.0, 40 % rest; static when reduce-motion), name variant iff name ≠ "user" (ci); links: source `https://github.com/yurtemre7/SonoraKMP`, developer `https://github.com/yurtemre7`, Telegram `https://t.me/emredev`. | |
| SET-08 | **Danger zone.** Hold 3 s (progress fill, cancel on move > touch slop/lift, light haptic start, heavy at end) → confirm → **full wipe**: stop playback & clear session, DataStore clear, Room `clearAllTables`, delete `filesDir/{playlist_covers,embedded_art}`, `cacheDir/*`, widget state, Coil caches, revoke persisted URI permissions, reset per-app locale → onboarding; snackbar `reset_done` (FIX D-01). | After reset, re-onboarding shows no previous playlists/favorites/stats/covers. |
| SET-09 | **Debug caches** (debug builds only): env, sync state, counts, artist-image map, songs list, root file listing; refresh; read-only. | Not present in release (R8 + `BuildConfig.DEBUG` gate). |
| SET-10 | **Settings repository.** All keys §5.3 with defaults; Flows; invalid values → default; settings reload after restore without restart (except locale). | Defaults test (port of `settings_persistence_test`). |

### 4.5 Statistics (STAT)

| ID | Feature & behaviour | Acceptance |
|---|---|---|
| STAT-01 | **Listen sessions.** `ListenSessionTracker`: recent milestone `clamp(round(d×0.25),10 s,30 s).coerceAtMost(d)` → `lastPlayedAt=now`; complete milestone 80 % of duration (position-based) → play++, completeListens++, weekday (Sun=0) ++, first-played song/date (`yyyy-MM-dd` local), playlist++ if context. Reset on item change, auto-advance/repeat loop, seek < 3 s. Timer delay `(milestone−pos)/speed`, +500 ms tolerance; only while playing; duration ≤ 0 never counts. audit/data §11.1. | Tracker tests with virtual clock. |
| STAT-02 | **Skips/restarts/shuffle.** Skip on UI next when not last; restart on previous > 3 s or seek < 3 s from > 3 s; shuffle session on enabling shuffle / quickShuffle. Recording pausable (nesting counter) during restore. | |
| STAT-03 | **Aggregates.** completeListens, shuffle sessions, total skips/restarts, unique songs played, playlists with plays, albums/artists listened (library-based), first played song/date, most active day (localized weekday name; `—` if none; tie → lowest key), top songs/albums/artists/playlists (limit 5, desc, stable), recent songs (newest first, tie titleLower/artistLower/id). audit/data §11.2. | Aggregate tests. |
| STAT-04 | **Activity views.** all / recentlyPlayed / mostPlayed / leastPlayed (never-played = 0 first). | |
| STAT-05 | **Stats screen.** Top bar title + reset (confirm); HorizontalPager 5 pages + bottom prev/dots/next: Overview (hero complete listens, 2×3 metrics grid albums/artists/playlists/shuffle sessions/skips/restarts, Recently played card, fun facts), Top songs (rank #1–#5 gold/silver/bronze tokens, expandable per-song plays/skips/restarts; tap plays top list — FIX D-16), Top albums (→ Album), Top artists (→ Artist), Top playlists (count = existing songs, FIX D-18 → Playlist). Empty states per page. audit/ui §7. | |
| STAT-06 | **Reset.** Clears all stats tables in one transaction. | |
| STAT-07 | **Persistence.** Room tables (§5.2), each recorded event one transaction (no 30 s debounce needed; atomic — FIX D-29). | |

### 4.6 Backup (BAK)

| ID | Feature & behaviour | Acceptance |
|---|---|---|
| BAK-01 | **Export v1.** Schema §5.5; `format="sonora-backup"`, `version=1`, `createdAt` UTC ISO-8601 ms, `appVersion="<VERSION_NAME>+<VERSION_CODE>"`; songs = referenced only (playlists → favorites → stats order); playlists ids filtered to library; favorites albums/artists maps; stats snapshot filtered (first played → −1 if absent); settings whitelist §5.5 with Flutter types (StringList as JSON array of strings); pretty 2-space. Empty library → `LibraryEmpty`. | Golden JSON test. |
| BAK-02 | **Parse/validate.** Strict UTF-8, strip BOM; errors `InvalidJson`, `NotABackup`, `UnsupportedVersion` (≠ 1), `Malformed` (typed rules audit/data §12.3; unknown keys ignored; bool fields reject ints); nothing written on failure. | Error-case table test. |
| BAK-03 | **Song matcher.** Refs & library sorted by id; pass 1 exact path; pass 2 tag key (`trim.lower` title\0artist\0album) unclaimed within 2000 ms closest, tie lowest id; pass 3 last-2-segment tail ∧ (title ∨ artist equal) within 2000 ms. One-to-one. audit/data §12.4. | Pinned matcher tests. |
| BAK-04 | **Restore plan & apply.** Planner pure → `RestorePlan`; semantics audit/data §12.5 (settings overwrite + remove `mfx_preset_name`/`mfx_customEqGains` when absent from a settings section; favorites albums/artists merge keeping existing dates; playlists merge by id, backup wins, unmatched dropped, dedup, covers kept; favorite songs union; stats replaced with remap, collisions summed, lastPlayed max). Apply in one Room transaction + DataStore edit with snapshot rollback; failure → `WriteFailed`. Idempotent. Stats recording paused. Result counts. | Restoring same file twice → identical DB dump. |
| BAK-05 | **Flutter compatibility.** Accept every Flutter 1.28 key; map legacy: `use_material_you`/`use_dynamic_theme` → `theme_color_source` (only if no `theme_color_source` in file); legacy `mfx_preset` speed / `mfx_is*` bools → preset (priority superSlowed > slowed > superSpedUp > spedUp > nightcore); `eq_*`, `sort_by*`, `dynamic_theme_color` ignored. Export writes both legacy bools and new keys so Flutter can import KMP backups. | Fixture: a real Flutter 1.28.1 backup imports with 0 errors; KMP export validates against Flutter's rules (fixture checked by test). |
| BAK-06 | **UI messages.** success parts `backup_restore_success`, `playlist_count(n)`, `backup_restored_favorites(n)`, `backup_unmatched_songs(n)`, `backup_restart_needed` (only if locale changed); errors → `backup_error_unsupported_version` / `backup_error_invalid_file` / `backup_error_library_empty` / `backup_error_no_matching_songs` / `backup_error_restore_failed` / `backup_error_rolled_back` / `backup_error_file_picker`. | |

### 4.7 Updates (UPD) — full spec §7

| ID | Feature | Acceptance |
|---|---|---|
| UPD-01 | Release fetch (latest / list) via Ktor | MockEngine tests 200/403/429/500/offline |
| UPD-02 | SemVer parse & compare incl. pre-release, `-dev` strip, `v` prefix, `+build` ignore | table tests |
| UPD-03 | Pre-release opt-in toggle `include_prereleases` | list endpoint used; drafts ignored |
| UPD-04 | Asset + checksum resolution (`sonora-<v>.apk`, `.sha256`) | missing asset → `NoAsset` state, browser fallback |
| UPD-05 | Auto check on launch ≤ once / 6 h, release builds, `auto_check_updates` | clock test |
| UPD-06 | Update sheet: changelog markdown, Later / Download / Open in browser | UI test |
| UPD-07 | Manual check from Info with result messages | |
| UPD-08 | Download with progress + cancel, SHA-256 verify, PackageInstaller session, install permission flow, all states | instrumented manual test + unit tests for verifier/state machine |

### 4.8 Localization (L10N) — §8

| ID | Feature | Acceptance |
|---|---|---|
| L10N-01 | All 429 Flutter keys in `values/strings.xml` + `values-ja/strings.xml` (snake_case) | `strings_parity` unit test: en and ja key sets equal; lint `MissingTranslation` clean |
| L10N-02 | 9 plurals as `<plurals>` (en one/other; ja other); `listens_count` zero via separate `listens_never` | |
| L10N-03 | Per-app language: picker System/English/日本語 → `AppCompatDelegate.setApplicationLocales`; `generateLocaleConfig` (en, ja); `app_locale` mirrors choice for backup | switching to ja updates UI without restart; system settings shows app languages |
| L10N-04 | Formerly hardcoded English moved to resources (list §8.3) | grep of `Text("` literals in `A/` returns none (lint check task) |
| L10N-05 | Dates/numbers via locale (`DateFormat.getBestDateTimePattern`, relative day labels, month-year, weekday names) | |
| L10N-06 | Non-UI text (notification, Auto, widget) resolved via service `Context` (follows app locale) | |

### 4.9 Android Auto (AUTO)

| ID | Feature & behaviour | Acceptance |
|---|---|---|
| AUTO-01 | **Browse tree.** Root (`root`) → `root_songs` / `root_albums` / `root_artists` / `root_playlists` (browsable) → `album:<name>|||<artist>`, `artist:<name>`, `playlist:<id>` → playable `song:<id>`; recent root → `[]`. Children paged (`page`, `pageSize`). audit/playback §10. | Media3 `MediaBrowser` test against service in Robolectric. |
| AUTO-02 | **Artwork.** `ArtworkProvider` (`${applicationId}.artwork`, exported read-only, `openFile` for `song/<id>`, `album/<key>`, `artist/<key>`, `playlist/<id>`); grants via `grantUriPermission` to `com.google.android.projection.gearhead`/Automotive or `android:grantUriPermissions` + public read of art only (FIX D-30). Album → first song art; artist → local image else first art; playlist → cover else first art. | DHU shows covers. |
| AUTO-03 | **getItem** for categories, songs, groups. | |
| AUTO-04 | **Search** case-insensitive substring title/artist/album, max 20. | |
| AUTO-05 | **Play from id.** song → whole library from song (parity); album/artist/playlist → from 0. Via `onSetMediaItems` resolution (start index). | |
| AUTO-06 | **Play from search.** artist (eq/contains) → album → song title → else **shuffle whole library** (empty query also → shuffle, FIX D-32), `isShuffled=true`. | |
| AUTO-07 | **Labels.** Category titles & "N songs" from resources (`plurals song_count`) (FIX). | |

### 4.10 Home-screen widget (WID)

| ID | Feature & behaviour | Acceptance |
|---|---|---|
| WID-01 | **Provider & info.** `NowPlayingWidgetProvider` (exported=false receiver for APPWIDGET_UPDATE), minWidth 250dp, minHeight 40dp, minResize 180×40, resize h|v, target 4×1, updatePeriod 0, preview = small, description string. | |
| WID-02 | **Layouts.** Small (`OPTION_APPWIDGET_MIN_HEIGHT < 100dp`): art 48 dp, title 14sp/artist 12sp single-line, prev / play-pause (circular primary bg) / next 40 dp; Large: art 96 dp, text block over centered buttons; padding 8/12 dp; radius 20 / art 12 dp (API 31 system radii). audit/playback §12.3. | |
| WID-03 | **State store.** SharedPreferences `sonora_home_widget`: `has_track`, `title`, `artist`, `art_path`, `playing`; renderer on single-thread executor, `goAsync`. | |
| WID-04 | **Content.** Track: title/artist/art (inSampleSize decode, center-crop, rounded, ≤ 384 px) or placeholder; none: `widget_nothing_playing` / `widget_tap_to_open`, launcher icon. Pause icon only if service alive ∧ playing. | |
| WID-05 | **Actions.** Art/text → MainActivity; buttons → `PendingIntent`s to `SonoraPlaybackService` (Media3 `MediaButtonReceiver` key events); service not running ∧ has track → start service with `onPlaybackResumption` restoring last queue (upgrade) else open app. | Tap play on cold widget resumes last song. |
| WID-06 | **Sync.** Service `Player.Listener` pushes (title, artist, artPath, isPlaying) deduped; close → nothing playing. Colors: Afterglow tokens light/dark (no dynamic) (D-33). | |

### 4.11 Accessibility (A11Y)

| ID | Feature | Acceptance |
|---|---|---|
| A11Y-01 | Every icon/button has `contentDescription`/tooltip from resources | lint + UI test sampling |
| A11Y-02 | Min touch target 48 dp | Compose `assertTouchHeightIsEqualTo` sampled |
| A11Y-03 | Song row merged semantics "Title, Artist, 3:42, now playing" (`now_playing_a11y`) | semantics test |
| A11Y-04 | Custom accessibility actions on rows: play next, add to queue, favorite; on queue rows: remove, move up/down | |
| A11Y-05 | 200 % font scale: no clipped titles; marquee disabled when fontScale > 1.3 | |
| A11Y-06 | `ContrastTest`: onPrimary/primary ≥ 4.5:1 for all sources; album-art fallback to `Contrast.Medium` | unit test |
| A11Y-07 | Reduce motion (`ANIMATOR_DURATION_SCALE == 0`): no cookie rotation, visualizer, vinyl spin, carousel auto-scroll, heartbeat | |
| A11Y-08 | Seek bar & sliders expose `progressBarRangeInfo` + set-progress action; lyrics lines are buttons "seek to" | |

---

## 5. Data models & persistence

### 5.1 Kotlin models (`S/model/`)

```kotlin
data class Song(
  val id: Long, val title: String, val artist: String, val album: String, val durationMs: Long,
  val filePath: String, val artworkPath: String?, val format: String?, val bitrate: Int?, val sampleRate: Int?,
  val lastModifiedMs: Long?, val fileSize: Long?, val hasLyrics: Boolean, val seedColor: Int?,
  val trackNumber: Int?, val discNumber: Int?, val genre: String?, val year: Int?, val albumId: Long?,
  val replayGain: ReplayGainInfo?, val replayGainScanned: Boolean,
  val isFavorite: Boolean = false, val favoriteDateMs: Long? = null,      // joined from favorite_songs
) { val titleLower; val artistLower; val albumLower /* lazy */ }
data class Playlist(val id: String, val name: String, val songIds: List<Long>, val coverPath: String?, val description: String?)
class AlbumGroup(val name: String, val artist: String, val songs: List<Song>)   // identity semantics; key() = "nameLower|||artistLower"
class ArtistGroup(val name: String, val songs: List<Song>, val albums: List<AlbumGroup>, val localImagePath: String?)
enum class ReplayGainMode { Off, Track, Album }                              // stored "off"/"track"/"album"
data class ReplayGainInfo(val trackGain: Double?, val albumGain: Double?, val trackPeak: Double?, val albumPeak: Double?)
enum class MfxPreset(val speed: Float) { SuperSlowed(.70f), Slowed075(.75f), Slowed080(.80f), Slowed(.85f), Slowed090(.90f),
  Slowed095(.95f), Normal(1f), SpedUp105(1.05f), SpedUp110(1.10f), SpedUp115(1.15f), SpedUp120(1.20f), SpedUp(1.25f),
  Nightcore(1.30f), SuperSpedUp(1.50f) }                                     // storage name = Flutter enum name (superSlowed…)
enum class EqPreset(val fractions: List<Double>) { Flat, Pop, Rock, Jazz, Classical }
enum class EqMode { Off, Custom, BassBoost, Warmth, LoFi }
enum class SongActivityView { All, RecentlyPlayed, MostPlayed, LeastPlayed }
enum class RepeatMode { Off, All, One }
enum class NowPlayingStyle { Modern, Vinyl, Minimalist }
enum class ThemeColorSource { Afterglow, System, AlbumArt, Custom }
enum class ThemeModeSetting { System, Light, Dark }
enum class AppLocale { System, English, Japanese }                          // "system"/"en"/"ja"
enum class SongSort { Title, Artist, Duration, Recent, Plays }; enum class AlbumSort { Name, Artist, Tracks, Recent }
enum class ArtistSort { Name, Albums, Songs }; enum class PlaylistSort { Name, Songs }; enum class FavoritesSort { Name, Duration, Date }
data class QueueEntry(val uid: Long, val songId: Long)
data class QueueState(val entries: List<QueueEntry>, val original: List<QueueEntry>, val currentIndex: Int,
  val isShuffled: Boolean, val repeat: RepeatMode, val playlistContext: String?, val nextUid: Long)
data class Lyrics(val lines: List<LyricLine>, val isSynchronized: Boolean); data class LyricLine(val timeMs: Long, val text: String)
data class AppSettings(/* one field per key in §5.3 with defaults */)
```

### 5.2 Room (`SonoraDatabase`, file `databases/sonora.db`, version 1, schemas exported to `shared/schemas`)

| Table | Columns (PK **bold**) | Indices / notes |
|---|---|---|
| `songs` | **id** L, title, artist, album, duration_ms, file_path, artwork_path?, format?, bitrate?, sample_rate?, last_modified_ms?, file_size?, has_lyrics, seed_color?, track_number?, disc_number?, genre?, year?, album_id?, rg_track_gain?, rg_album_gain?, rg_track_peak?, rg_album_peak?, rg_scanned | unique(file_path) |
| `favorite_songs` | **song_id**, file_path, favorited_at_ms | relink by path on sync |
| `favorite_albums` | **key** ("albumLower|||artistLower"), favorited_at_ms | |
| `favorite_artists` | **key** (artistLower), favorited_at_ms | |
| `playlists` | **id** S, name, description?, cover_path?, created_at_ms, position | |
| `playlist_entries` | **playlist_id**, **position**, song_id | FK → playlists ON DELETE CASCADE; index(song_id) |
| `artist_images` | **artist_key**, path | |
| `song_stats` | **song_id**, plays, skips, restarts, last_played_at_ms? | |
| `playlist_stats` | **playlist_id** S, plays | kept for deleted playlists (parity) |
| `weekday_stats` | **day** (0=Sun..6), plays | |
| `stats_summary` | **id**=0, complete_song_listens, first_played_song_id (−1), first_played_date?, shuffle_session_starts | single row |

DAOs: `SongDao`, `PlaylistDao`, `FavoritesDao`, `ArtistImageDao`, `StatsDao` (Flow queries + `@Transaction` writers).

### 5.3 DataStore keys (`files/datastore/sonora_settings.preferences_pb`)

Types: B Boolean, I Int, L Long, D Double, S String, SS StringSet. ★ = new key (not in Flutter). ⓑ = in backup whitelist.

| Key | Type | Default | Notes |
|---|---|---|---|
| theme_mode ⓑ | S | `system` | system/light/dark |
| theme_color_source ★ⓑ | S | `afterglow` | afterglow/system/album_art/custom |
| use_material_you ⓑ, use_dynamic_theme ⓑ | B | false / true | legacy mirrors written on change for Flutter backup compat |
| default_theme_color ⓑ | L | `0xFFE5573B` | accent for Custom & album-art fallback |
| amoled_dark ⓑ, blurred_backgrounds ⓑ | B | false | |
| now_playing_style ⓑ | S | `modern` | modern/vinyl/minimalist |
| prefer_local_artist_images ⓑ | B | true | |
| show_visualizer ⓑ, immersive_mode ⓑ, use_greeting_title ⓑ | B | false | |
| user_name ⓑ | S | `User` | |
| app_locale ⓑ | S | `system` | mirror of AppCompat locales |
| default_start_page ⓑ | I | 0 | 0..3 |
| keep_playing_on_close ⓑ | B | false | |
| restore_last_played_song ⓑ | B | true | |
| replay_gain_mode ⓑ | S | `off` | |
| pause_on_duck ⓑ | B | false | |
| pause_on_disconnect ⓑ | B | true | |
| resume_on_connect ⓑ | B | false | |
| mfx_bluetoothMfxPatch ⓑ | B | false | |
| sleep_timer_default_minutes ⓑ | I | 5 | {5,10,15,20,25,30,45,60} |
| sleep_timer_fade_out_seconds ⓑ | I | 10 | {0,10,30,60} |
| sleep_timer_finish_song ⓑ | B | false | |
| filter_title_features ⓑ, filter_title_artist ⓑ | B | false | |
| auto_check_updates ⓑ | B | true | |
| include_prereleases ★ | B | false | not backed up |
| song_sort_by ⓑ / song_sort_ascending ⓑ | S/B | `title`/true | |
| album_sort_by ⓑ / album_sort_ascending ⓑ | S/B | `name`/true | |
| artist_sort_by ⓑ / artist_sort_ascending ⓑ | S/B | `name`/true | |
| playlist_sort_by ⓑ / playlist_sort_ascending ⓑ | S/B | `name`/true | |
| favorites_sort_by ⓑ / favorites_sort_ascending ⓑ | S/B | `date`/false | |
| song_activity_view ⓑ | S | `all` | derived from songs sort |
| volume ⓑ, speed ⓑ | D | 1.0 | |
| mfx_preset_name ⓑ | S | unset | Flutter enum name |
| mfx_isReverbEnabled ⓑ, mfx_isLofiEnabled ⓑ, mfx_isBassBoostEnabled ⓑ, mfx_isCustomEqEnabled ⓑ | B | false | |
| mfx_customEqGains ⓑ | S | unset | JSON array of doubles; backup ↔ list of strings |
| last_played_song_id | L | unset | |
| last_played_queue_ids, last_played_original_queue_ids | S | unset | comma-separated ids |
| last_played_position_ms | L | 0 | |
| last_played_is_shuffled | B | false | |
| scan_folder_path | S | unset | filesystem path |
| scan_folder_uri ★ | S | unset | persisted tree URI |
| last_sync_timestamp | L | unset | |
| last_sync_duration_ms ★ | L | unset | replaces `last_sync_duration_last` |
| postpone_sync_until | L | 0 | |
| onboarding_completed | B | false | |
| colorless_artwork_paths | SS | ∅ | |
| last_update_check_ms ★ | L | 0 | 6 h throttle |
| update_rate_limit_reset_ms ★ | L | 0 | skip auto checks until then |

Dropped Flutter keys: `last_sync_time`, `last_perf_log`, `sync_method`, `metadata_version`, `sort_by*`,
`imported_songs_json`, `artist_images_json`, `playlists_json`, `favorite_*` (→ Room), `cached_changelog_content`,
`last_changelog_check_time`, `dynamic_theme_color`, `mfx_preset` & `mfx_is{Slowed,SuperSlowed,SpedUp,SuperSpedUp,Nightcore}`
(read only from backups), `eq_*`.

### 5.4 Files on disk

| Path | Content |
|---|---|
| `databases/sonora.db` | Room DB |
| `files/datastore/sonora_settings.preferences_pb` | settings |
| `cache/album_art/art_album_<albumId>.{jpg,none}` | MediaStore art cache (LIB-05) |
| `files/embedded_art/<songId>.jpg` | directory-scan embedded art |
| `files/playlist_covers/<playlistId>.jpg` | covers (512 px q85) |
| `cache/share/<name>.m3u` | export staging (cleared on start) |
| `cache/updates/sonora-<v>.apk` (+ `.part`) | updater downloads (deleted after install/cancel/on start if stale) |
| `cache/image_cache/` | Coil disk cache |
| `shared_prefs/sonora_home_widget.xml` | widget state |
| `assets/CHANGELOG.md` (APK) | bundled changelog |

### 5.5 Backup v1 JSON (must match Flutter byte-semantics, audit/data §12)

```json
{ "format": "sonora-backup", "version": 1, "createdAt": "2026-11-10T12:34:56.789Z", "appVersion": "1.29.0+512",
  "songs": [ {"id": 1, "path": "/storage/…/one.mp3", "title": "", "artist": "", "album": "", "durationMs": 0} ],
  "settings": { "<whitelisted key>": <bool|int|number|string|[string]> },
  "playlists": [ {"id": "p1", "name": "Mix", "description": null, "songIds": [1, 2]} ],
  "favorites": { "songs": [ {"id": 1, "favoriteDateMs": 1000} ],
                 "albums": {"<albumLower>|||<artistLower>": 1700000000000}, "artists": {"<artistLower>": 1700000000000} },
  "stats": { "complete_song_listens": 0, "first_played_song_id": -1, "first_played_date": null,
             "song_play_counts": {"<id>": n}, "playlist_play_counts": {"<pid>": n}, "weekly_play_counts": {"0": n},
             "shuffle_session_starts": 0, "song_skip_counts": {}, "song_restart_counts": {}, "song_last_played_at_ms": {} } }
```

Settings whitelist (type) = every ⓑ key in §5.3 **plus** Flutter keys accepted on import only: `dynamic_theme_color` I,
`sort_by` S, `sort_ascending` B, `sort_by_songs` S, `sort_ascending_songs` B, `mfx_preset` D, `mfx_isSlowed`,
`mfx_isSuperSlowed`, `mfx_isSpedUp`, `mfx_isSuperSpedUp`, `mfx_isNightcore` B, `eq_current_preset` S, `eq_custom_gains`
StringList, `eq_enabled` B. Flutter types: `default_theme_color`/`dynamic_theme_color` int (ARGB as positive int),
`volume`/`speed` decimal, `mfx_customEqGains` StringList. Unknown keys (e.g. `theme_color_source`) are ignored by
Flutter's validator → safe to export.

---

## 6. Navigation map

Navigation 3: one `SnapshotStateList<NavKey>` back stack (saved), `NavDisplay` with `rememberSaveableStateHolderNavEntryDecorator`
+ `rememberViewModelStoreNavEntryDecorator`. Gate is **not** in the back stack: `MainActivity` shows `GateHost` until
state = Ready, then the shell. Now Playing is an overlay owned by the shell, not a NavKey.

| NavKey (`@Serializable`) | Args | Screen | Entered from |
|---|---|---|---|
| `Library(tab)` | `tab: LibraryTab` (Songs/Albums/Artists/Playlists) | Home with tab | root; nav bar/rail switches tab by replacing root key |
| `Album(name, artist)` | String, String | Album detail | Albums tab, Artist carousel, Favorites, Stats, NP album link |
| `Artist(name)` | String | Artist detail | Artists tab, Favorites, Stats, NP artist link/picker |
| `Playlist(id)` | String | Playlist detail | Playlists tab, Stats |
| `Favorites` | – | Favorites | Home action |
| `RecentlyPlayed` | – | Recently played | Home action, Stats overview |
| `Stats` | – | Statistics | Settings |
| `Settings` | – | Settings root | Home action |
| `SettingsAppearance`, `SettingsFormatting`, `SettingsPlayback`, `SettingsPrivacy`, `SettingsBackup`, `SettingsInfo` | – | sub-screens | Settings |
| `Changelog`, `Licenses` | – | | Info |
| `DebugCaches` | – | debug only | Settings (debug) |
| `Onboarding` (gate) | – | | GateHost |

Overlays / sheets (ModalBottomSheet, not NavKeys): Now Playing (`NowPlayingOverlay`, states Collapsed/Expanded, view
Player/UpNext/Lyrics/Related), Sort sheet, Favorites sort, Song info, Playlist selector, Sleep timer, MFX, Artist picker,
Update sheet, dialogs.

Back behaviour (predictive back everywhere, `PredictiveBackHandler`):
1. Open sheet/dialog → dismiss it.
2. Multi-select active → clear selection.
3. Search field focused/non-empty on Home → clear focus, then query.
4. Now Playing expanded: in UpNext/Lyrics/Related → **return to Player view first**; in Player view → collapse
   (predictive progress drives collapse animation; DESIGN §4). (FIX D-34)
5. Back stack size > 1 → pop.
6. Root `Library(tab)` with tab ≠ default start page → switch to default tab; else finish activity.

Rules:
- Opening album/artist from Now Playing (album link, artist link, artist picker row) **collapses Now Playing and pushes
  the route** — detail screens have one hosting mode only (FIX D-35). Artist → album carousel pushes `Album` normally.
- Tapping the notification/widget opens the app with Now Playing expanded (intent extra `open_now_playing`).
- Missing entities (deleted album/playlist) render an inline "not found" state with back action.
- Transitions: shared-axis X push/pop, fade-through for tab switch, shared element mini player ↔ NP artwork/title.
- No deep links (parity).

---

## 7. Auto-update (new design; replaces Flutter's ABI-split updater)

**Source.** Repo `BuildConfig.GITHUB_REPO` = `yurtemre7/SonoraKMP`. Base `https://api.github.com`, header
`Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, `User-Agent: Sonora-Android` (no personal data), unauthenticated.

| Mode | Endpoint | Selection |
|---|---|---|
| stable (default) | `GET /repos/{repo}/releases/latest` | that release |
| pre-releases on (`include_prereleases`) | `GET /repos/{repo}/releases?per_page=20` | non-draft releases, highest SemVer (pre-releases included) |

**Versions (`SemVer`).** Parse `[vV]?MAJOR.MINOR.PATCH(-PRERELEASE)?(+BUILD)?`. Local = `BuildConfig.VERSION_NAME`
with a trailing `-dev` removed. Ordering per semver.org §11: numeric core; a version with pre-release < same core
without; pre-release identifiers compared dot-wise (numeric < alphanumeric, numeric numerically, ASCII otherwise,
shorter prefix lower); build metadata ignored. Unparseable remote tag → `Failed(Malformed)`. Update available iff
remote > local. A stable user is never offered a pre-release; a pre-release user with the toggle off is offered the next
stable that is greater.

**Release conventions (CI, P8).** Tag `v<version>` (e.g. `v1.29.0`, `v1.30.0-beta.1`); GitHub "prerelease" flag set
iff version has `-`; assets `sonora-<version>.apk` (universal, signed) and `sonora-<version>.apk.sha256`; body =
CHANGELOG section of that version (markdown). Checksum file format: `<64 hex>` optionally followed by whitespace +
filename (sha256sum format); `ChecksumParser` accepts both, case-insensitive hex.

**State machine (`UpdateState`).**

| State | Entered when | UI |
|---|---|---|
| `Idle` | initial | – |
| `Checking` | manual/auto check | spinner on Info tile |
| `UpToDate` | remote ≤ local | snackbar `already_on_latest` (manual only) |
| `Available(release)` | newer & asset found | Update sheet: title `update_available`, `update_available_message(version)`, changelog markdown (scroll, max 60 % height), buttons Later / Open in browser / Download |
| `Downloading(bytes, total?)` | Download tapped | determinate (or indeterminate) progress, Cancel (aborts coroutine + deletes `.part`) |
| `Verifying` | download complete | indeterminate |
| `ReadyToInstall(file)` | hash OK | Install button; if `!packageManager.canRequestPackageInstalls()` → explainer + "Allow" → `ACTION_MANAGE_UNKNOWN_APP_SOURCES` (`package:` uri), re-check on resume |
| `Installing` | session committed | system confirmation dialog (`STATUS_PENDING_USER_ACTION` → start the provided intent) |
| `Failed(reason)` | – | message + Retry / Open in browser |

`Failed` reasons: `NoNetwork` (no validated network via `ConnectivityManager` or IOException), `RateLimited(resetAt)`
(403/429 with `X-RateLimit-Remaining: 0` → `X-RateLimit-Reset` epoch s, or `Retry-After`; message shows localized reset
time; button opens `https://github.com/{repo}/releases`), `NoAsset` (release without matching apk/sha256 → browser),
`ChecksumMismatch` (file deleted), `DownloadFailed(http)`, `InstallFailed(status, message)`, `InstallCancelled`
(`STATUS_FAILURE_ABORTED`), `Malformed`.

**Download/install.** Ktor streaming GET (follows redirects to `objects.githubusercontent.com`), write
`cache/updates/sonora-<v>.apk.part` → rename on success; SHA-256 streamed while writing; compare with `.sha256` asset;
`PackageInstaller.createSession(MODE_FULL_INSTALL)` (API ≥ 31: `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` best
effort; API ≥ 34 `setRequestUpdateOwnership(true)`), write APK, commit with a mutable `PendingIntent` to
`InstallResultReceiver`; result → state. Manifest: `REQUEST_INSTALL_PACKAGES`. Signature mismatch (e.g. debug build)
→ `InstallFailed` with `update_error_signature` hint.

**When.** Auto: on cold start after gate Ready, release builds only, `auto_check_updates`, `now − last_update_check_ms
≥ 6 h`, `now ≥ update_rate_limit_reset_ms`; silent unless `Available` (sheet shown once per version per process).
Manual: Settings › Info › Check for updates (always runs, ignores throttle, shows all outcomes). Both write
`last_update_check_ms` on HTTP 200.

**Changelog screen** shows the bundled CHANGELOG (UI-36); the update sheet shows the release body.

---

## 8. Localization

### 8.1 Mechanics
- Default `values/` = English, `values-ja/` = Japanese. `androidResources { generateLocaleConfig = true; localeFilters += [en, ja] }`
  (already configured) + `res/resources.properties` `unqualifiedResLocale=en`.
- In-app picker (SET-01): System → `setApplicationLocales(LocaleListCompat.getEmptyLocaleList())`, English → `en`,
  日本語 → `ja`; labels always in their own language (`language_english`="English", `language_japanese`="日本語", both
  `translatable="false"`). `MainActivity` extends `AppCompatActivity`; manifest declares
  `AppLocalesMetadataHolderService` with `autoStoreLocales=true` for API < 33. `app_locale` mirrors the choice.
- Shared code returns enums/data; formatting happens in the app (`ui/format/` helpers for durations, sizes, dates).

### 8.2 Key convention
- snake_case of the Flutter ARB key: `albumCount` → `album_count`, `noMatchingSongsFound` → `no_matching_songs_found`,
  `mfxLoFi` → `mfx_lo_fi`, `eqPop` → `eq_pop`. Acronyms split per word (`importM3u` → `import_m3u`).
- Placeholders → positional (`{name}` → `%1$s`, ints → `%1$d`); `%` literals → `%%` in formatted strings; escape `'`,
  `"`, leading `@`/`?`; preserve leading spaces with ` ` (`sync_duration`).
- ICU plurals → `<plurals>`: `album_count`, `artist_count`, `playlist_count`, `song_count`, `track_count`, `plays_count`,
  `listens_count`, `backup_restored_favorites`, `backup_unmatched_songs`; en `one`/`other`, ja `other`. `=0` cases
  (`listens_count`) → separate `listens_never` chosen in code.
- Widget keys keep their existing names (`widget_*`).
- New keys (formerly hardcoded) use feature prefixes: `gate_*`, `onboarding_*`, `snackbar_*`/`sync_*`, `update_*`,
  `debug_*` (debug-only strings may live in `src/debug/res`), `notification_*`, `auto_*`, `a11y_*`.
- Process: P1 generates all 429 keys (en+ja) from `docs/audit/strings.tsv` by script; P5 tasks add **new** strings to
  `AR/values/strings_<feature>.xml` + `AR/values-ja/strings_<feature>.xml` (disjoint files); P6 merges them into
  `strings.xml` and translates missing ja.

### 8.3 Formerly hardcoded strings that become resources (audit/ui §13.1, audit/settings §10)
Gate screens (loading slow, failed to load + explanation + hint, Force close, Clear data & retry, Access to music files
+ body, Grant permission); onboarding wordmark "SONORA" (`translatable=false`); folder configured / imported N
snackbars; "Sync reminder postponed for 1 month."; "Remind Next Month"; " in Xms"; "Library and settings cleared
successfully!"; "No playlists created yet"; `Added "x" to y.` / `Removed "x" from y.`; `Removed "x" from playlist.`;
"Sharing N songs"; "(orig.)"; route not-found texts; "DEV" (`translatable=false`); "No changelog available.";
updater strings (§7); "Close player"; Auto root labels & "N songs"; "Copy Performance Benchmark Logs" etc. (debug only);
weekday names for most-active-day; About footer.

---

## 9. Decisions & deviations

### 9.1 Bugs fixed (not copied)

| # | Flutter behaviour | Kotlin |
|---|---|---|
| D-01 | Danger zone clears prefs only; files, covers, cache, in-memory settings survive; message overstates | Full wipe (SET-08) |
| D-02 | Custom EQ switch off → `resetAllMfx()` (resets speed/preset/toggles) | Only disables custom EQ |
| D-03 | Up Next swipe removes first entry with same song id; original queue drops all same-id entries; no undo | Entry-uid based removal + Undo |
| D-04 | Mini player swipe callbacks named inversely | Finger direction: left = next, right = previous |
| D-05 | Seek bar wraps minutes at 60, no hours | `h:mm:ss` ≥ 1 h |
| D-06 | `pause_on_disconnect=false` ignored (just_audio always pauses); resume-on-connect racy | Setting honoured; flag set before pause |
| D-07 | Swipe-down stops & clears queue with no undo | Undo snackbar 4 s |
| D-11 | Unshuffle uses stale broadcast position (audible jump) | Live position, no reload when possible |
| D-12 | Repeat-all wraps to window start | Wraps to logical start |
| D-13 | Reorder outside window restarts current track | `moveMediaItem`, never restarts |
| D-15 | Related tab enabled by artist OR album match, content needs artist | Enabled iff content non-empty |
| D-16 | Related / Stats top-song tap queues whole library | Queue = the visible list |
| D-17 | Song info audio group visibility uses `replayGain` vs rows | Shown iff a row has data |
| D-18 | Top playlists count raw ids incl. missing | Existing songs only |
| D-19 | Directory sync exception returns `[]` (empty library UI) | Returns cache |
| D-20 | Background-extracted art appears only on next sync | Rows patched when extraction finishes |
| D-21 | Embedded art files leak on every re-parse; favorite date/seed lost on re-parse | Stable file per song id; carry-over by path |
| D-22 | Artist images: pref shadows file, native writes unread dir, "any image" fallback | Single Room table, no "any image" fallback |
| D-23 | Artist sort `songs` falls back to name | Implemented |
| D-24 | Playlist id ms collisions | Bump until unique |
| D-25 | M3U import suffix match (`a.mp3` ~ `ba.mp3`), unsanitized export name | Exact path / exact file name; sanitized |
| D-26 | Favorites lost when MediaStore id changes | Relink by file path |
| D-27 | LRC `.5` = 5 ms; `[offset:]` ignored | Decimal fraction; offset applied |
| D-28 | Changelog fetched from GitHub raw `main`, no offline | Bundled asset |
| D-29 | Stats JSON non-atomic, one bad key resets all | Room transactions |
| D-30 | Android Auto art via `file://` (not loadable in car process); English labels; flat unpaged Songs | content:// provider, localized, paged |
| D-32 | Empty/unmatched voice search plays first artist | Shuffle whole library |
| D-34 | System back in NP sub-views closes the whole sheet | Back → player view first |
| D-35 | Album/Artist opened from NP as nested sheets; artist→album imperative push | Collapse NP + push route |
| D-36 | `last_sync_time` stored as English 12 h string | Timestamp stored, formatted at display in locale |
| D-37 | Update "Cancel" doesn't cancel download; no integrity check; checks every launch, unthrottled | §7 |
| D-38 | `syncLibraryLegacy` wiping, sort pre-sort with unused keys, `metadata_version` | dropped |
| D-39 | Playlist-selector single mode / sync snackbars hardcoded English | resources |

### 9.2 Intentional behaviour/identity changes

| # | Change |
|---|---|
| D-08 | Media notification custom buttons: Close when no timer; +1 min & End timer when timer active (Android 13+ shows only 2). |
| D-09 | Media3 default foreground policy (notification dismissible while paused → acts as Close) instead of permanent foreground. |
| D-10 | Media3 default media-button handling (double-tap headset hook = next) accepted. |
| D-14 | Sleep timer notification actions run in the service without opening the app; chronometer countdown instead of 1 s reposts. |
| D-31 | "Open folder" strategies reduced to: Samsung My Files, ACTION_VIEW directory mime, DocumentsUI tree with initial URI, storage settings; `<queries>` limited accordingly. |
| D-33 | Widget uses Afterglow color tokens (no Material You dynamic colors); layouts/behaviour unchanged. |
| D-40 | Visual identity: Afterglow (DESIGN.md) — Bricolage/Figtree, Ember palette, morphing Pulse button, Ember wave seek bar, Afterglow backdrop replacing blurred cover by default. |
| D-41 | Color source gets a 4th option **Afterglow** (new default; Flutter default was Album art). Album-art seed uses Palette vibrant→dominant (Flutter: mean color) → themes look different by design. Default accent `#E5573B` (Flutter `#7C4DFF`). |
| D-42 | Navigation: adaptive nav bar/rail with library tabs instead of an in-page TabBar; Now Playing is one overlay (§6). |
| D-43 | Updater: universal APK + checksum + PackageInstaller session; ABI chooser removed; pre-release channel added. |
| D-44 | Persist playback position every 15 s while playing (Flutter: only on events). |
| D-45 | Permission gate offers "Open settings" when permanently denied. |
| D-46 | No in-place data migration from the Flutter app (different applicationId); backup import is the path. |
| D-47 | Images: Coil caches replace ThumbnailStore/ImagePreflight (no 128 px PNG files). |
| D-48 | Stats count songs by id like Flutter; MediaStore id changes still break stats/playlists (only favorites relink). |

### 9.3 Kept quirks (parity, documented)
Artist splitting on `/ & x with` (`AC/DC`); unknown-artist songs lose their album; album grouping by name with artist
heuristics; no album-artist tag; play-next LIFO stacking; ≤ 0.05 volume restores to 1.0 after fades; ReplayGain cannot
amplify (clamp 1.0), clip guard uses track peak in album mode; complete listen = 80 % position (seeking counts); skip
counted on any UI "next" except last; repeat not persisted; sleep timer not persisted; search without accent/kana
folding; `recent` sort = file mtime; total-duration format omits minutes when hours present; MediaStore `trackNumber`
raw (disc×1000+track); onboarding forces greeting title on with default name "User".

### 9.4 Dropped
iOS (Documents/Music import, `addMusic*` flows — strings kept only if reused), Windows (audio_service_win,
just_audio_windows, Windows volume default 0.1), `QueueScreen` (unrouted), `SongActivityView.recentlyPlayed` song-tab
branch, `openStats`, Hero tags without sources, `saveQueueIds`/`saved_queue_ids.json`, `legacySyncLibrary`, sync
benchmark/perf logs (debug screen shows sync duration only), `scanFile`, `getMediaVolume`, `getDeviceAbi`,
`stopForegroundService`, flutter_local_notifications boot receivers, `deleteSong` (no UI caller), "Clear app data & retry"
kept only on the error gate. **DevBadge kept** (debug builds). **Debug caches screen kept, debug builds only.**

---

## 10. Known gaps & risks

| # | Risk | Mitigation |
|---|---|---|
| R-01 | ReplayGain via player volume lands ~one buffer late at gapless boundaries | Accept; option: custom `AudioProcessor` gain stage later |
| R-02 | Pause-on-duck needs custom focus handling; interaction with Media3 internal focus | Switch `handleAudioFocus` per setting; Robolectric test |
| R-03 | Equalizer availability/OEM quirks; session id changes | Recreate on `onAudioSessionIdChanged`; hide EQ if creation fails |
| R-04 | Large queues (10k+) over MediaSession binder | Sliding window kept (PLAY-08); test with 10k items |
| R-05 | SAF tree URI → filesystem path conversion for SD cards/OEM volumes | Fallback DocumentFile scan; store both path & URI |
| R-06 | jaudiotagger speed/compat on large libraries; `File` access to non-media files on API 30+ | MediaStore primary; fallback only when MediaStore empty |
| R-07 | Song id = MediaStore `_ID` may change on re-index → playlists/stats lose refs | Backup matcher; favorites relink by path (D-26); playlists/stats not relinked (Q-1) |
| R-08 | Material3 1.5.0-rc01 API churn (expressive) | Pin; isolate expressive usages in `ui/components/` |
| R-09 | Navigation 3 + predictive back + shared elements maturity | Keep NP overlay outside NavDisplay |
| R-10 | PackageInstaller flows differ by OEM; first install of KMP build over Flutter impossible (different id) | Browser fallback; README explains side-by-side |
| R-11 | GitHub unauthenticated rate limit 60/h/IP | 6 h throttle + reset-time persistence |
| R-12 | Backup compat drift if Flutter changes schema after 1.28.1 | Fixture from 1.28.1 in tests |
| R-13 | Palette seeds differ from Flutter mean colors | Accepted (D-41); ContrastTest fallback |
| R-14 | Android Auto content:// art permissions (projection vs. Automotive) | Provider grants + DHU manual test |
| R-15 | Per-app locale on API < 33 requires AppCompat + autoStoreLocales | MainActivity is AppCompatActivity |
| R-16 | Visualizer remains fake (no RECORD_AUDIO) | Documented |

### Open questions
- **Q-1** Should playlist entries and stats also relink by file path when MediaStore ids change (like favorites)? Spec: no (parity), revisit.
- **Q-2** Release signing key for KMP builds is new; Flutter users can never in-place upgrade. Confirm product wants side-by-side permanently.
- **Q-3** Japanese translations for new strings (§8.3) need native review.
- **Q-4** Should "Skip this version" exist in the update sheet? Not specified; omitted.
