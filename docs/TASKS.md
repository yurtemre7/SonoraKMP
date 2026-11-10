# Sonora KMP — Task Plan

Companion to `docs/SPEC.md` (feature IDs in brackets refer to SPEC §4). Each task is sized for one agent in one go.
Within a **batch** all tasks own **disjoint** paths and may run in parallel; batches run in order. A task may read
anything but writes only what it owns (plus its own test file only if listed). Tasks never commit.

Path shorthands: `S/` = `shared/src/commonMain/kotlin/de/yurtemre/sonora/shared/`, `SA/` = `shared/src/androidMain/kotlin/de/yurtemre/sonora/shared/`,
`ST/` = shared host tests (`shared/src/androidHostTest/kotlin/de/yurtemre/sonora/shared/`, or `commonTest` — fixed in P1-01),
`A/` = `androidApp/src/main/kotlin/de/yurtemre/sonora/`, `AR/` = `androidApp/src/main/res/`, `AT/` = `androidApp/src/test/kotlin/de/yurtemre/sonora/`.
Commands (confirmed in P1-01; shared commonTest runs under `testAndroidHostTest`; AboutLibraries via plugin `com.mikepenz.aboutlibraries.plugin.android` → `R.raw.aboutlibraries` and written into `CONTRIBUTING.md` in P8): `BUILD_SHARED` = `./gradlew :shared:compileAndroidMain`,
`BUILD_APP` = `./gradlew :androidApp:assembleDebug`, `TEST_SHARED` = `./gradlew :shared:testAndroidHostTest`, `TEST_APP` = `./gradlew :androidApp:testDebugUnitTest`, `LINT` = `./gradlew :androidApp:lintDebug`.
New user-visible strings during P5–P6 go into `AR/values/strings_<task>.xml` (+ `AR/values-ja/strings_<task>.xml`); P6-01 merges them.

---

## P1 Foundation (batch P1a → P1b → P1c)

**P1a**
- [x] **P1-01** Build baseline & catalog additions — deps: [] — owns: `gradle/libs.versions.toml`, `build.gradle.kts`, `settings.gradle.kts`, `shared/build.gradle.kts`, `androidApp/build.gradle.kts`, `androidApp/proguard-rules.pro` — done when: markdown renderer + AboutLibraries + `androidx.sqlite:sqlite-framework` (test) added with pinned versions, test source sets configured, `BUILD_APP` and an empty `TEST_SHARED` succeed, real task names noted at top of this file.
- [x] **P1-02** Shared models — deps: [P1-01] — owns: `S/model/**` — done when: all types of SPEC §5.1 exist (enums with stable storage names matching Flutter strings, `AppSettings` with every §5.3 default), `BUILD_SHARED` passes.
- [x] **P1-03** Strings skeleton from audit — deps: [P1-01] — owns: `scripts/strings/**`, `AR/values/strings.xml`, `AR/values-ja/strings.xml` — done when: a script converts `docs/audit/strings.tsv` → 429 snake_case keys en+ja (placeholders positional, 9 `<plurals>`, `listens_never`, escaping per SPEC §8.2) plus `app_name`, `language_*`, widget strings; `LINT` reports no `MissingTranslation`/format errors.

**P1b**
- [x] **P1-04** Settings repository — deps: [P1-02] — owns: `S/settings/**` — done when: `SettingsKeys` covers every §5.3 key, `SettingsRepository` exposes `Flow<AppSettings>` + typed setters + legacy-bool mirroring for color source, `PlaybackStateStore` (last_played_*), compiles.
- [x] **P1-05** Room schema — deps: [P1-02] — owns: `S/db/**`, `shared/schemas/**` — done when: all tables of §5.2 with DAOs (Flow reads, `@Transaction` writers, `clearAll`), `SonoraDatabase` with `@ConstructedBy`, schema v1 JSON exported, `BUILD_SHARED` passes.
- [x] **P1-06** Repository contracts — deps: [P1-02] — owns: `S/repo/api/**` — done when: interfaces `LibraryRepository`, `PlaylistRepository`, `FavoritesRepository`, `StatsRepository`, `BackupRepository`, `LibraryScanner`, `FileStore`, `Clock` with KDoc referencing SPEC IDs compile.
- [x] **P1-07** Theme (Sleeve) — deps: [P1-01] — owns: `A/ui/theme/**`, `AR/values/themes.xml`, `AR/values-night/themes.xml` — done when: `SonoraTheme(source, mode, amoled, seed)` builds hand-tuned Sleeve schemes (MaterialKolor TonalSpot for album-art/custom seeds), AMOLED overrides, Bricolage Grotesque typography scale, shapes, `MotionScheme.expressive()`, splash theme; preview renders.
- [x] **P1-08** Format helpers — deps: [P1-03] — owns: `A/ui/format/**` — done when: duration (`m:ss`, `h:mm:ss`, total parts via resources), file size, date/time, relative day, month-year, weekday name helpers exist.

**P1c**
- [x] **P1-09** Platform & shared DI — deps: [P1-04, P1-05] — owns: `SA/platform/**`, `SA/di/**` — done when: Room builder (bundled driver, `databases/sonora.db`), DataStore factory (`sonora_settings`), `AndroidFileStore`, `sharedModule` (repos bound later via placeholders) compile.
- [x] **P1-10** Playback contract — deps: [P1-02] — owns: `A/playback/api/**` — done when: `PlaybackConnection` interface + `PlayerUiState`, `QueueUiState`, `SleepTimerUiState`, `MfxUiState`, command methods for every PLAY-* user action are declared.
- [x] **P1-11** Manifest & component stubs — deps: [P1-01] — owns: `androidApp/src/main/AndroidManifest.xml`, `AR/xml/**`, stub classes `A/playback/SonoraPlaybackService.kt`, `A/playback/ArtworkProvider.kt`, `A/widget/NowPlayingWidgetProvider.kt`, `A/update/InstallResultReceiver.kt` — done when: permissions (§3.3), service filters + FGS type, MediaButtonReceiver, FileProvider + `file_paths.xml`, artwork provider, widget receiver + info xml, `automotive_app_desc.xml`, locale service, `<queries>` (D-31) declared; `BUILD_APP` and `LINT` pass.
- [x] **P1-12** App DI & navigation shell — deps: [P1-07, P1-09, P1-10] — owns: `A/SonoraApplication.kt`, `A/MainActivity.kt`, `A/di/**`, `A/ui/navigation/**`, stub files `A/ui/screens/*/<Feature>Route.kt` and `A/ui/screens/*/<Feature>Module.kt` for every feature in SPEC §2.1 — done when: AppCompatActivity hosts gate placeholder → `NavDisplay` with all NavKeys of SPEC §6 routed to stub routes, adaptive scaffold stub, NP overlay slot, Koin starts; `BUILD_APP` launches on emulator.
- [x] **P1-13** Core UI components — deps: [P1-03, P1-07] — owns: `A/ui/components/{AlbumArt,ArtistAvatar,PlaceholderArt,SongRow,EmptyState,ConfirmDialog,SectionHeader,DevBadge}.kt` — done when: components per DESIGN §3/§5.5 with Coil size hints (128/512/800 px) compile with previews.

## P2 Core logic in shared (batch P2a → P2b), each with KDoc citing audit sections

**P2a**
- [x] **P2-01** Artist parsing & grouping [LIB-09..11] — deps: [P1-02] — owns: `S/library/{ArtistParser,AlbumGrouper,ArtistGrouper}.kt` — done when: implements audit/data §5.1–5.3 incl. `buildAlbumGroup/buildArtistGroup` lookups; compiles.
- [x] **P2-02** Sorting & scroll sections [LIB-12, UI-07] — deps: [P1-02] — owns: `S/library/{LibrarySorter,ScrollSection}.kt` — done when: all sort enums/directions incl. artist `songs`, plays via activity view hook, favorites sort.
- [x] **P2-03** Search index [LIB-13] — deps: [P1-02] — owns: `S/library/LibrarySearchIndex.kt` — done when: AND-substring songs search + sorted cache keyed by sort/direction, album/artist/playlist filters.
- [x] **P2-04** Title, duration, color buckets [LIB-14, LIB-15, LIB-22] — deps: [P1-02] — owns: `S/library/{TitleFormatter,DurationFormat,SeedColorBuckets}.kt` — done when: displayTitle rules, `DurationParts`, Flutter-HSL bucket function implemented.
- [x] **P2-05** QueueState core [PLAY-02,05,06] — deps: [P1-02] — owns: `S/queue/{QueueState,QueueOps}.kt` — done when: load, playNext (LIFO), add, remove by uid, move with index bookkeeping, skipTo, entry-uid generation, original-queue bookkeeping.
- [x] **P2-06** ReplayGain reader [LIB-08] — deps: [P1-02] — owns: `S/audio/replaygain/**` — done when: 1:1 port of audit/data §9.1 over a `ByteSource` abstraction (ID3v2.2–2.4, FLAC, Ogg Vorbis/Opus R128, MP4), never throws, `failed` flag.
- [x] **P2-07** Audio math [PLAY-09,10,12–16] — deps: [P1-02] — owns: `S/audio/{ReplayGainMath,SpeedPresets,EqGains,FadeCurve}.kt` — done when: multiplier, preset table, EQ mode/BT/preset mapping, 20-step fade curve match SPEC constants.
- [x] **P2-08** Sleep timer reducer [PLAY-26] — deps: [P1-02] — owns: `S/audio/SleepTimerState.kt` — done when: pure reducer for start/tick/extend/stop/finish-song/fade volume outputs.
- [x] **P2-09** Lyrics parser [LIB-24] — deps: [P1-02] — owns: `S/lyrics/**` — done when: LRC (decimal fraction, offset, multi-stamp) + plain parser.
- [x] **P2-10** M3U codec [LIB-18, LIB-19] — deps: [P1-02] — owns: `S/playlist/**` — done when: encoder, decoder, exact-path/file-name matcher, name sanitizer, playlist-name-from-file.
- [x] **P2-11** Listen session tracker [STAT-01, STAT-02] — deps: [P1-02] — owns: `S/stats/ListenSessionTracker.kt` — done when: event-driven tracker emits `RecordRecent/RecordComplete/RecordSkip/RecordRestart/RecordShuffleSession` with milestone scheduling and pause nesting.
- [x] **P2-12** Stats aggregates [STAT-03, STAT-04] — deps: [P1-02] — owns: `S/stats/{StatsAggregates,ActivityView,StatsSnapshot}.kt` — done when: all aggregates of audit/data §11.2 over a `StatsSnapshot` + library.
- [x] **P2-13** Backup schema & codec [BAK-01, BAK-02, BAK-05] — deps: [P1-02] — owns: `S/backup/{BackupV1,BackupCodec,BackupError,SettingsWhitelist}.kt` — done when: encode/parse/validate with all error kinds, whitelist incl. import-only Flutter keys and legacy mappings.
- [x] **P2-14** Song matcher [BAK-03] — deps: [P1-02] — owns: `S/backup/SongMatcher.kt` — done when: 3-pass one-to-one matcher per audit/data §12.4.
- [x] **P2-15** SemVer & checksum [UPD-02, UPD-04] — deps: [P1-02] — owns: `S/update/{SemVer,ChecksumParser}.kt` — done when: semver.org ordering, `-dev` strip, `v` prefix, build ignored; sha256sum parser.
- [x] **P2-16** GitHub releases client [UPD-01, UPD-03] — deps: [P1-01] — owns: `S/update/{GitHubReleasesClient,Release,RateLimit}.kt` — done when: latest + list endpoints, headers, rate-limit header parsing, injected `HttpClient`.

**P2b**
- [x] **P2-17** Shuffle, repeat, navigation rules [PLAY-03, PLAY-04, PLAY-07] — deps: [P2-05] — owns: `S/queue/{Shuffle,QueueNavigation}.kt` — done when: shuffle on/off (injectable Random), quickShuffle, repeat cycle, next/previous with 3 s rule & repeat semantics.
- [x] **P2-18** Restore planner [BAK-04] — deps: [P2-13, P2-14] — owns: `S/backup/RestorePlanner.kt` — done when: pure plan (settings ops, favorites merge, playlist merge, stats remap) + result counts, idempotent.
- [x] **P2-19** Update checker [UPD-01..05] — deps: [P2-15, P2-16] — owns: `S/update/UpdateChecker.kt` — done when: returns `UpToDate/Available/Failed(reason)` incl. throttle decision helper (6 h, rate-limit reset) given injected clock & settings values.

## P3 Android data (batch P3a → P3b → P3c)

**P3a**
- [x] **P3-01** MediaStore scanner [LIB-03] — deps: [P1-09] — owns: `SA/scan/MediaStoreScanner.kt` — done when: port of `queryMediaStore` (selection, variants, escaping, fallbacks, lyrics flag) returning raw rows.
- [x] **P3-02** Album art cache [LIB-05] — deps: [P1-09] — owns: `SA/scan/ArtworkCache.kt` — done when: extraction (720 px q90 / albumart URI), markers, cold pool ≤ 5 min, background mode with callback, prune.
- [x] **P3-03** Tag reader [LIB-06] — deps: [P1-09] — owns: `SA/scan/TagReader.kt` — done when: jaudiotagger read with `MediaMetadataRetriever` fallback, embedded picture bytes, never throws.
- [x] **P3-04** Artist images & lyrics locator [LIB-07, LIB-24] — deps: [P1-09] — owns: `SA/scan/{ArtistImageScanner,LyricsLocator}.kt` — done when: single walk algorithm; sidecar lookup + read.
- [x] **P3-05** Seed color extractor [LIB-22] — deps: [P1-09] — owns: `SA/scan/SeedColorExtractor.kt` — done when: Palette vibrant→dominant, null on failure.
- [x] **P3-06** Platform helpers [LIB-01, LIB-17] — deps: [P1-09] — owns: `SA/platform/{TreeUriPaths,PlaylistCoverProcessor}.kt` — done when: tree URI ↔ path conversion (primary + volume uuid), 512 px q85 center crop.
- [x] **P3-07** Playlist repository [LIB-16..19] — deps: [P1-06, P2-10] — owns: `S/repo/impl/PlaylistRepositoryImpl.kt` — done when: CRUD, dedupe add, reorder, cover replace (old deleted), M3U import/export via `FileStore`.
- [x] **P3-08** Favorites repository [LIB-20] — deps: [P1-06] — owns: `S/repo/impl/FavoritesRepositoryImpl.kt` — done when: songs/albums/artists toggle/bulk, relink by path API.
- [x] **P3-09** Stats repository [STAT-01..07] — deps: [P1-06, P2-12] — owns: `S/repo/impl/StatsRepositoryImpl.kt` — done when: record events transactionally, snapshot Flow, reset, pause/resume nesting, replace-all for restore.
- [x] **P3-10** App platform helpers — deps: [P1-11] — owns: `A/platform/{ShareHelper,UrlOpener,FolderOpener,MediaVolume}.kt` — done when: share files (single/multi), open URL, folder opener strategies (D-31), ensure media volume.

**P3b**
- [x] **P3-11** Directory scanner [LIB-06] — deps: [P3-03, P3-06] — owns: `SA/scan/DirectoryScanner.kt` — done when: DocumentFile/File walk, extension set, cover files, fallbacks, embedded art `files/embedded_art/<id>.jpg`, id allocation.
- [x] **P3-12** Android library scanner [LIB-03..07] — deps: [P3-01, P3-02, P3-04, P3-11] — owns: `SA/scan/AndroidLibraryScanner.kt` — done when: implements `LibraryScanner` (MediaStore first, directory fallback, artist images, unchanged rule inputs).
- [x] **P3-13** Backup repository [BAK-01..06] — deps: [P2-18, P3-07, P3-08, P3-09] — owns: `S/repo/impl/BackupRepositoryImpl.kt` — done when: export from DB/DataStore, apply plan in one transaction with rollback snapshot, `WriteFailed` on error.

**P3c**
- [x] **P3-14** Library repository [LIB-04, 08, 21, 22, 23] — deps: [P3-12, P2-06, P2-01, P3-05] — owns: `S/repo/impl/LibraryRepositoryImpl.kt` — done when: sync orchestration (missing folder protection, exception → cache), RG fill fan-out 4, favorites relink, seed-color background pass (3 s, 100 ms), groups/index Flows, sync reminder computation.
- [x] **P3-15** Wire shared DI — deps: [P3-13, P3-14] — owns: `SA/di/**` — done when: all repos/scanners bound in `sharedModule`; app resolves `LibraryRepository` at runtime on emulator.

## P4 Playback, Auto, widget (batch P4a → P4b → P4c)

**P4a**
- [x] **P4-01** Player & ForwardingPlayer [PLAY-04, 09, 10, 11] — deps: [P2-07, P2-17] — owns: `A/playback/{PlayerFactory,SonoraForwardingPlayer,VolumeComposer}.kt` — done when: ExoPlayer (audio attrs, `WAKE_MODE_LOCAL`), fades via coroutine, volume = user × fade × RG, repeat-one next/prev = seek 0, completed play restarts at 0.
- [x] **P4-02** Queue controller [PLAY-02..08] — deps: [P2-05, P2-17] — owns: `A/playback/QueueController.kt` — done when: window 10/30, slide 20/15, edits mirrored, repeat-all gapless wrap, request tokens, `onMediaItemTransition` → current index + RG before broadcast.
- [x] **P4-03** Audio effects [PLAY-12..16] — deps: [P2-07] — owns: `A/playback/AudioEffects.kt` — done when: Equalizer bound to session id (recreated on change), modes/BT patch/custom bands, speed/pitch application, unsupported → flag.
- [x] **P4-04** Focus, noisy, reconnect [PLAY-17..19] — deps: [P1-04] — owns: `A/playback/{AudioFocusController,NoisyAndReconnectHandler}.kt` — done when: pause-on-duck custom focus, noisy honours setting, device-callback resume.
- [x] **P4-05** Sleep timer [PLAY-26, 27] — deps: [P2-08] — owns: `A/playback/sleep/**` — done when: timer driving reducer, finish-song poll 250 ms, notification channel `sleep_timer` id 8888 chronometer + actions to service.
- [x] **P4-06** Stats recorder & position ticker [STAT-01, PLAY-25] — deps: [P2-11, P3-09] — owns: `A/playback/{ListenStatsRecorder,PositionTicker}.kt` — done when: tracker wired to player events, 200 ms ticker with 1500 ms backward filter.
- [x] **P4-07** Persistence [PLAY-24] — deps: [P1-04, P2-05] — owns: `A/playback/PlaybackPersistence.kt` — done when: save triggers incl. 15 s periodic, restore paused at position, clear on close.
- [x] **P4-10** Home-screen widget [WID-01..06] — deps: [P1-11, P1-03] — owns: `A/widget/**`, `AR/layout/widget_*.xml`, `AR/drawable/widget_*.xml`, `AR/values*/widget_{colors,dimens}.xml` — done when: small/large layouts, state store, renderer, PendingIntents to service, `WidgetUpdater` Player.Listener with dedupe.

**P4b**
- [x] **P4-11** Playback service [PLAY-01, 20–23, 28; WID] — deps: [P4-01..P4-07, P4-10] — owns: `A/playback/{SonoraPlaybackService,SessionCommands,NotificationButtons}.kt` — done when: MediaLibrarySession with callback (custom commands for queue/MFX/timer/close), notification button preferences (D-08), task-removed, close/clear, `onPlaybackResumption`, ensure volume; plays a song from adb-started controller.

**P4c**
- [x] **P4-12** PlaybackConnection impl — deps: [P4-11] — owns: `A/playback/PlaybackConnectionImpl.kt` — done when: MediaController-backed StateFlows for `PlayerUiState`/queue/timer/MFX and all command methods; bound in Koin via own module file `A/playback/PlaybackModule.kt`.

## P5 UI screens; each task also owns its `AR/values*/strings_<task>.xml`

### P5.1 Usable app (first installable build)
Goal: open the app, grant access, pick a folder, see your songs, play them, control playback.

**P5.1a** (parallel)
- [ ] **P5-01** Startup gate [UI-01, LIB-02] — deps: [P1-12, P3-15] — owns: `A/ui/screens/gate/**`, `A/permissions/**` — done when: Loading/Slow/Error/Permission states with 15 s/30 s timers, clear-data retry, open-settings on permanent denial.
- [ ] **P5-02** Onboarding [UI-02, LIB-01] — deps: [P1-12, P3-15] — owns: `A/ui/screens/onboarding/**` — done when: 3 pages, permission rows, folder picker, finish flow + snackbar; greeting only when a name is entered (D-64).
- [ ] **P5-03** Home scaffold, header, search, sort sheet [UI-03..06] — deps: [P1-12, P2-02, P2-03] — owns: `A/ui/screens/home/{HomeScreen,HomeViewModel,HomeHeader,SearchHeader,SortSheet}.kt` — done when: greeting/actions/progress, shared query, per-tab buttons & counts, sort sheet with plays special case.
- [ ] **P5-05** Fast scroller & multi-select [UI-07, UI-13] — deps: [P1-13] — owns: `A/ui/components/{FastScroller,MultiSelectActionBar,SelectionState}.kt` — done when: thumb/bubble/haptics; selection state holder + action bar with all actions & snackbars.
- [ ] **P5-06** Mini player & Pulse button [UI-15] — deps: [P4-12, P1-13] — owns: `A/ui/components/{MiniPlayer,PulseButton}.kt` — done when: gestures (up/down+undo/left/right), progress hairline, morphing rotating cookie with reduce-motion check.
- [ ] **P5-07** Now Playing overlay & player view [UI-16, UI-17, UI-22] — deps: [P4-12] — owns: `A/ui/screens/nowplaying/{NowPlayingOverlay,NowPlayingViewModel,PlayerView,ArtworkArea,ArtistPickerSheet}.kt`, `A/ui/components/{TintedBackdrop,Vinyl,Visualizer,MarqueeText}.kt` — done when: expand/collapse with predictive back, view switching with back-to-player, styles/immersive, chips, controls, collapse+push for album/artist.
- [ ] **P5-08a** Up Next & seek bar [UI-18, UI-21] — deps: [P4-12, P2-09] — owns: `A/ui/screens/nowplaying/UpNextView.kt`, `A/ui/components/WavySeekBar.kt` — done when: reorder/swipe-remove+undo, wavy seek bar with h:mm:ss.

**P5.1b**
- [ ] **P5-04a** Songs tab & sync banner [UI-08, UI-12] — deps: [P5-03, P5-05] — owns: `A/ui/screens/home/tabs/**` — done when: Songs tab list (tap plays the list from that song, song menu: play next / add to queue / favorite), empty/no-result states, pull-to-refresh sync, sync banner behaviours.
- [ ] **P5-23** First usable nightly — deps: [P5.1 tasks] — owns: none (fixes go to owners) — done when: on the emulator, a fresh install goes onboarding → folder pick → scan → Songs tab → tap plays → mini player → Now Playing (seek, skip, shuffle, repeat, Up Next); nightly published; maintainer installs it on a phone.

### P5.2 Everything else (built while the app is in daily use)

**P5.2a** (parallel)
- [ ] **P5-04b** Albums, Artists, Playlists tabs [UI-09..11] — deps: [P5-04a] — owns: `A/ui/screens/home/tabs/{Albums,Artists,Playlists}*.kt` — done when: 3 tab contents with sort, search, empty states and playlist menu actions.
- [ ] **P5-08b** Lyrics & Related views [UI-19, UI-20] — deps: [P5-08a, P2-09] — owns: `A/ui/screens/nowplaying/{LyricsView,RelatedView}.kt` — done when: synced lyrics auto-scroll (4 s grace, tap-seek), plain lyrics, related lists.
- [ ] **P5-09** Sleep timer & MFX sheets [UI-31, UI-32] — deps: [P4-12] — owns: `A/ui/screens/nowplaying/sheets/**` — done when: both sheets incl. custom EQ (vertical sliders, presets, hidden when unsupported).
- [ ] **P5-10** Shared sheets & dialogs [UI-28..30] — deps: [P1-13, P3-15] — owns: `A/ui/sheets/**` — done when: song info, playlist selector (single/multi), rename/description/create/save-queue dialogs.
- [ ] **P5-11** Album & Artist detail [UI-23, UI-24] — deps: [P1-12, P3-15] — owns: `A/ui/screens/album/**`, `A/ui/screens/artist/**` — done when: headers, play/shuffle/favorite, lists with menu & multi-select, not-found states.
- [ ] **P5-12** Playlist detail [UI-25] — deps: [P1-12, P3-15] — owns: `A/ui/screens/playlist/**` — done when: header/description edit, menu, reorderable list, swipe remove + undo, empty state.
- [ ] **P5-13** Favorites & Recently played [UI-26, UI-27] — deps: [P1-12, P3-15] — owns: `A/ui/screens/favorites/**`, `A/ui/screens/recent/**` — done when: sections, auto-scroll carousel, favorites sort sheet, recent day grouping.
- [ ] **P5-14** Statistics [STAT-05, STAT-06] — deps: [P1-12, P3-15] — owns: `A/ui/screens/stats/**` — done when: 5-page pager with nav, all cards, reset confirm.

**P5.2b** (parallel)
- [ ] **P5-15** Settings root & language [SET-01, L10N-03] — deps: [P1-12] — owns: `A/ui/screens/settings/{SettingsScreen,SettingsViewModel,LibrarySyncCard,LanguageDialog}.kt`, `A/locale/**` — done when: sync card, stats link, language picker via AppCompat (System/English/日本語), sub-screen tiles, debug tile.
- [ ] **P5-16** Appearance & formatting [SET-02, SET-03, UI-33] — deps: [P1-12] — owns: `A/ui/screens/settings/appearance/**`, `A/ui/screens/settings/formatting/**` — done when: all controls persist; swatch pie selector; live preview.
- [ ] **P5-17** Playback & privacy settings [SET-04, SET-05] — deps: [P1-12, P4-12] — owns: `A/ui/screens/settings/playback/**`, `A/ui/screens/settings/privacy/**` — done when: every control persists and reaches the service immediately; privacy cards match manifest.
- [ ] **P5-18** Backup settings [SET-06, BAK-06] — deps: [P3-13] — owns: `A/ui/screens/settings/backup/**` — done when: export/import with SAF, confirm dialog, summary/error snackbars (6 s), progress.
- [ ] **P5-19** Info & danger zone [SET-07, SET-08] — deps: [P1-12] — owns: `A/ui/screens/settings/info/**`, `A/platform/AppWiper.kt` — done when: about dialog, links, thank-you card, hold-3-s danger zone performing the full wipe of SET-08 and routing to onboarding.
- [ ] **P5-20** Changelog & licenses [UI-36, UI-37] — deps: [P1-01] — owns: `A/ui/screens/changelog/**`, `A/ui/screens/licenses/**` — done when: bundled markdown renders with h1–h3 styles offline; AboutLibraries list + OFL fonts.
- [ ] **P5-21** Debug caches [SET-09] — deps: [P1-12] — owns: `A/ui/screens/debug/**` — done when: read-only sections, reachable only when `BuildConfig.DEBUG`.
- [ ] **P5-22** Update UI [UPD-06, UPD-07] — deps: [P2-19] — owns: `A/update/{UpdateViewModel,UpdatePromptHost}.kt`, `A/ui/screens/update/**` — done when: update sheet (markdown body, Later/Browser/Download), Info tile states, rate-limit dialog with reset time; download/install actions call P7 interfaces (stubbed).

## P6 Localization, accessibility, adaptive (batch P6a → P6b)

**P6a**
- [ ] **P6-02** Hardcoded-literal sweep [L10N-04] — deps: [P5-*] — owns: `A/ui/screens/**`, `A/ui/sheets/**`, `scripts/check_hardcoded_strings.sh` — done when: script finds no `Text("…")`/literal user text in `A/`; offenders moved to `strings_l10n_sweep.xml`.
- [ ] **P6-03** Accessibility pass [A11Y-01..05, A11Y-08] — deps: [P5-*] — owns: `A/ui/components/**` — done when: content descriptions, 48 dp targets, merged row semantics, custom actions, slider range info, marquee off at large font scale.
- [ ] **P6-04** Reduce motion & theme a11y [A11Y-06, A11Y-07] — deps: [P1-07] — owns: `A/ui/theme/**` — done when: `LocalReduceMotion` from animator scale consumed by motion tokens; album-art contrast fallback to `Contrast.Medium`.
- [ ] **P6-05** Adaptive layouts [UI-34] — deps: [P5-*] — owns: `A/ui/navigation/**`, `A/ui/screens/nowplaying/NowPlayingTwoPane.kt` — done when: compact/medium/expanded scaffolds, list-detail on expanded, NP two-pane in landscape, 840 dp max width for settings.
- [ ] **P6-06** Locale-aware formatting & non-UI strings [L10N-05, L10N-06] — deps: [P1-08, P4-11] — owns: `A/ui/format/**` — done when: all dates/weekdays/month-year via locale APIs; notification/Auto/widget strings resolve with app locale (verified in ja).

**P6b**
- [ ] **P6-01** Merge & translate strings [L10N-01, L10N-02] — deps: [P6-02..P6-06] — owns: `AR/values/strings*.xml`, `AR/values-ja/strings*.xml` — done when: all staging files merged into `strings.xml` (en + ja, every new key translated), staging files deleted, `LINT` clean.

## P7 Auto-update installer end-to-end

- [ ] **P7-01** Downloader & verifier [UPD-08] — deps: [P2-19] — owns: `A/update/{ApkDownloader,Sha256Verifier}.kt` — done when: streaming `.part` download with progress Flow, cancel deletes partial, streaming SHA-256 compare.
- [ ] **P7-02** PackageInstaller session [UPD-08] — deps: [P1-11] — owns: `A/update/{PackageInstallerHelper,InstallResultReceiver}.kt` — done when: session create/write/commit, pending-user-action intent launched, statuses mapped to `Failed` reasons.
- [ ] **P7-03** Install permission & state machine [UPD-08] — deps: [P7-01, P7-02, P5-22] — owns: `A/update/{InstallPermissionHelper,UpdateViewModel}.kt` — done when: all §7 states reachable; unknown-sources settings round-trip re-checks on resume.
- [ ] **P7-04** Launch scheduling [UPD-05] — deps: [P7-03] — owns: `A/update/{UpdateScheduler,UpdatePromptHost}.kt` — done when: release-only auto check ≤ 1/6 h, rate-limit reset respected, sheet once per version per process.
- [ ] **P7-05** End-to-end verification — deps: [P7-04, P8-02] — owns: none — done when: a pre-release `vX.Y.Z-rc.1` published by CI is offered (toggle on), downloaded, verified and installed over a lower signed release on a device; mismatch & cancel paths observed.

## P8 CI/CD & repo docs

- [x] **P8-01** CI workflow — deps: [P1-01] — owns: `.github/workflows/ci.yml` — done when: on PR/push runs lint, unit tests, debug build with Gradle cache, JDK 17. (Green on GitHub.)
- [x] **P8-02** Release workflow — deps: [] — owns: `.github/workflows/release.yml`, `cliff.toml` — done when: a `release: X.Y.Z` commit on main (or dispatch with `dry_run`) builds signed `sonora-<v>.apk` + `.sha256` + `.aab`, versionCode derived from versionName, git-cliff notes (grouped, credits, compare link), prerelease iff `-`; tags `vX.Y.Z`.
- [x] **P8-08** Nightly workflow — owns: `.github/workflows/nightly.yml` — done when: daily build of main replaces the rolling `nightly` pre-release (skipped if unchanged), release-signed, ignored by the updater.
- [x] **P8-03** Pages workflow — deps: [P9-01] — owns: `.github/workflows/pages.yml` — done when: deploys `/site` on changes under `site/**` (needs Pages enabled with source "GitHub Actions").
- [ ] **P8-04** Bundled changelog — deps: [P1-01] — owns: `CHANGELOG.md`, `androidApp/build.gradle.kts` (asset task only) — done when: `CHANGELOG.md` generated with `git cliff` config and copied into generated assets so UI-36 shows it offline.
- [x] **P8-05** CONTRIBUTING — deps: [P8-01] — owns: `CONTRIBUTING.md` — done when: commit convention, commands, release process (tag `vX.Y.Z`), strings process documented.
- [ ] **P8-06** README — deps: [P8-02] — owns: `README.md` — done when: features, install (side-by-side with Flutter app, backup migration), build, release, one-time secrets setup, Pages setup, privacy, links.
- [x] **P8-07** Release dry run — deps: [P8-02] — owns: none — done when: `release.yml` dispatched with `dry_run` produces apk/sha256/aab artifacts and notes. (Run 42814606411: signed with release key, checksum matches.)

## P9 Website (`/site`)

- [x] **P9-01** Site scaffold & tokens — owns: `site/**` — done when: Sleeve tokens, self-hosted woff2, light/dark toggle, responsive.
- [x] **P9-02** Hero & features — done when: Pulse-button hero, feature grid, screenshot slots (placeholders until P9-05).
- [x] **P9-03** Download — done when: latest release APK via GitHub API with static fallback, EN/JA toggle.
- [ ] **P9-04** Site QA — deps: [P8-07] — owns: `site/**` (fixes only) — done when: download button resolves a real release asset, no console errors, a11y checks pass.
- [ ] **P9-05** Real screenshots — deps: [P10-27] — owns: `site/assets/screenshots/*.png`, `images/**` — done when: emulator screenshots of home, now playing, library, settings replace placeholders.

## P10 Tests & final verification (all tasks in one batch, then P10-27)

Shared unit tests (`ST/`):
- [x] **P10-01** ArtistParser/AlbumGrouper tests — deps: [P2-01] — owns: `ST/library/GroupingTest.kt` — done when: audit/data §5 examples (808, Greatest Hits, unknown artist, AC/DC) pass.
- [x] **P10-02** Sorter & search tests — deps: [P2-02, P2-03] — owns: `ST/library/SortSearchTest.kt` — done when: each sort option/direction + search examples of audit/data §6 pass.
- [x] **P10-03** Title & duration tests — deps: [P2-04] — owns: `ST/library/FormatTest.kt` — done when: Cosmos/Starlight, total-duration examples, `h:mm:ss`, bucket function.
- [x] **P10-04** QueueState tests — deps: [P2-17] — owns: `ST/queue/QueueStateTest.kt` — done when: shuffle history, unshuffle, LIFO play next, remove by uid, move bookkeeping, repeat cycle, 3 s previous rule.
- [x] **P10-05** ReplayGain reader tests — deps: [P2-06] — owns: `ST/audio/ReplayGainReaderTest.kt`, `shared/src/*Test/resources/rg/**` — done when: ID3v2.3/2.4, FLAC, Vorbis, Opus R128, MP4 fixtures and malformed inputs.
- [x] **P10-06** Audio math tests — deps: [P2-07] — owns: `ST/audio/AudioMathTest.kt` — done when: −6 dB ×0.501, clip guard, EQ ±15 dB pinned values, preset mapping N=5/10, fade steps.
- [x] **P10-07** Sleep timer tests — deps: [P2-08] — owns: `ST/audio/SleepTimerStateTest.kt` — done when: 6 pinned behaviours of audit/playback §14.
- [x] **P10-08** Lyrics & M3U tests — deps: [P2-09, P2-10] — owns: `ST/lyrics/LyricsParserTest.kt`, `ST/playlist/M3uCodecTest.kt` — done when: fraction/offset/multi-stamp/plain; export format, exact-name matching, sanitizing.
- [x] **P10-09** Listen tracker tests — deps: [P2-11] — owns: `ST/stats/ListenSessionTrackerTest.kt` — done when: 25 %/10–30 s and 80 % milestones under speed changes, seek reset, pause nesting.
- [x] **P10-10** Stats aggregates tests — deps: [P2-12] — owns: `ST/stats/StatsAggregatesTest.kt` — done when: top lists, most active day, activity views.
- [x] **P10-11** Backup codec tests — deps: [P2-13] — owns: `ST/backup/BackupCodecTest.kt`, `shared/src/*Test/resources/backup/**` — done when: real Flutter 1.28.1 backup fixture parses; every error kind; export validates under Flutter rules.
- [x] **P10-12** Matcher & planner tests — deps: [P2-18] — owns: `ST/backup/RestorePlannerTest.kt` — done when: 3 passes, 2000 ms window, merges, idempotence.
- [x] **P10-13** SemVer & checksum tests — deps: [P2-15] — owns: `ST/update/SemVerTest.kt` — done when: semver.org §11 example chain, `-dev` strip, `v`/`+build`, sha256sum formats.
- [x] **P10-14** UpdateChecker tests — deps: [P2-19] — owns: `ST/update/UpdateCheckerTest.kt` — done when: MockEngine 200 newer/older, prerelease list mode, 403/429 with reset, 500, offline, missing asset, 6 h throttle.
- [ ] **P10-15** Settings defaults test — deps: [P1-04] — owns: `ST/settings/SettingsRepositoryTest.kt` — done when: fresh DataStore yields every §5.3 default; setters round-trip; color-source legacy mirroring.

App tests (`AT/`, Robolectric):
- [x] **P10-16** DAO tests — deps: [P1-05, P3-07] — owns: `AT/data/DaoTest.kt` — done when: playlist add dedupe/remove-all/reorder, favorites relink, stats transaction, clearAll.
- [x] **P10-17** ForwardingPlayer fade test — deps: [P4-01] — owns: `AT/playback/FadeTest.kt` — done when: virtual-time ramp values, reversal, ≤ 0.05 → 1.0 quirk, volume composition with RG.
- [x] **P10-18** PositionTicker test — deps: [P4-06] — owns: `AT/playback/PositionTickerTest.kt` — done when: 6 pinned ticker behaviours.
- [ ] **P10-19** Strings parity test — deps: [P6-01] — owns: `AT/l10n/StringsParityTest.kt` — done when: en/ja string & plural key sets identical, format args consistent.
- [ ] **P10-20** Contrast test — deps: [P6-04] — owns: `AT/theme/ContrastTest.kt` — done when: onPrimary/primary ≥ 4.5:1 for Sleeve/System-fallback/AlbumArt samples/Custom swatches, light & dark & AMOLED.
- [ ] **P10-21** Sync banner UI test — deps: [P5-04] — owns: `AT/ui/SyncBannerTest.kt` — done when: 3 cases of audit/ui §2.9.
- [ ] **P10-22** Multi-select bar UI test — deps: [P5-05] — owns: `AT/ui/MultiSelectBarTest.kt` — done when: "1 selected", select-all/close callbacks, 5 actions.
- [ ] **P10-23** Dialogs UI test — deps: [P5-10] — owns: `AT/ui/DialogsTest.kt` — done when: confirm true/false, rename prefill/submit, description blank → null.
- [ ] **P10-24** Song info UI test — deps: [P5-10] — owns: `AT/ui/SongInfoSheetTest.kt` — done when: metadata shown, selectable path, Copy path, tap value → snackbar, audio group hidden when empty.
- [ ] **P10-25** Now Playing back test — deps: [P5-07] — owns: `AT/ui/NowPlayingBackTest.kt` — done when: back from Up Next returns to player; back from player collapses; album link collapses and pushes `Album`.
- [ ] **P10-26** App smoke test — deps: [P5-01, P5-02] — owns: `AT/SmokeTest.kt` — done when: Robolectric launches `MainActivity` with fake repos → onboarding; with completed onboarding + fake library → Songs tab lists songs.
- [ ] **P10-28** Room migrations before 1.0 — deps: [] — owns: `SA/platform/**`, `shared/schemas/**` — done when: `fallbackToDestructiveMigration` is removed, the released schema is frozen as version 1 (or bumped with a migration), and a migration test covers every later schema change.
- [ ] **P10-27** Final verification — deps: [P10-01..P10-26, P6-01, P7-05, P8-01] — owns: none (fixes go back to owning tasks) — done when: `BUILD_APP`, assembleRelease (R8), `LINT`, `TEST_SHARED`, `TEST_APP` green; manual device checklist passed: scan, play/gapless, notification + BT buttons, sleep timer, widget, backup import of a Flutter backup, language switch, danger zone, update flow.

---

## Intentionally dropped (not ported)

| Item | Reason |
|---|---|
| iOS & Windows code paths (Documents/Music import, audio_service_win, just_audio_windows, Windows volume 0.1) | Android-only port |
| Flutter prefs/library-cache migration in place | Different applicationId; backup v1 import is the migration path |
| ABI-split APKs + ABI chooser in update dialog | Universal APK + checksum (SPEC §7) |
| Fetching CHANGELOG.md from GitHub raw | Bundled asset |
| `QueueScreen` (unrouted), `openStats`, song-tab `recentlyPlayed` view branch, Hero tags without sources | Dead code in Flutter |
| `saveQueueIds`/`saved_queue_ids.json`, `legacySyncLibrary`, `scanFile`, `getMediaVolume`, `getDeviceAbi`, `stopForegroundService`, `deleteSong` | No callers / obsolete with Media3 |
| ThumbnailStore PNGs, ImagePreflightService, 150 MB/250-image Flutter cache caps | Coil caches |
| Sync performance benchmark logs & "Copy Performance Benchmark Logs" | Debug noise; debug screen shows sync duration |
| flutter_local_notifications boot/scheduled receivers | No scheduled notifications |
| 17-package file-manager `<queries>` list & generic chooser strategies | Reduced folder-opener strategies (D-31) |
| Material You dynamic colors in the widget | Sleeve tokens (D-33) |
| Legacy pref keys (`sort_by*`, `metadata_version`, `eq_*`, `dynamic_theme_color`, `mfx_is{Slowed,…}`, `mfx_preset`) | Read only when importing Flutter backups |
| Android Auto (browse tree, artwork provider, voice search) | Not needed (D-52) |
| Kept but limited: DevBadge (dev builds), Debug caches screen (debug builds only) | Developer aid |
