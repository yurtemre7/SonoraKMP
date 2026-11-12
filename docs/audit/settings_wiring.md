# Settings wiring audit (P5-33)

Snapshot of `wip/p11`, 2026-11-12. Paths are relative to `androidApp/src/main/kotlin/de/yurtemre/sonora/`
(app) or `shared/src/commonMain/kotlin/de/yurtemre/sonora/shared/` (shared). Every setter lives in
`shared/settings/SettingsRepository.kt`; keys in `shared/settings/SettingsKeys.kt`.

Marks: ✅ applied immediately · 🕑 applied later (when noted) · ❌ stored but never read at runtime ·
⚠️ read but wrong/partial · 🚧 being built (P5-28..30).
Backup: **B** = in `backup/SettingsWhitelist.exportKeys` (export + import), **–** = not in backup.

Test coverage: `shared/src/androidHostTest/.../settings/SettingsTest.kt` (one test) checks every SPEC §5.3
default on a fresh DataStore, round-trips every typed setter, the start-page clamp, `recordSync` clearing
postpone, and the legacy `use_material_you`/`use_dynamic_theme` mirror in both directions. Green.
It proves storage only; runtime effects below are not covered by tests.

## Appearance (`ui/screens/settings/appearance/SettingsAppearanceRoute.kt`)

| Setting | UI control | Runtime reader | Effect / timing | Mark | Bak |
|---|---|---|---|---|---|
| themeMode | ConnectedChoice | `MainActivity.kt:38` → SonoraTheme | recomposition | ✅ | B |
| themeColorSource | ConnectedChoice | `MainActivity.kt:37` | System/Afterglow work; **AlbumArt and Custom fall back to Sleeve** because MainActivity passes no `seedColor` (`ui/theme/Theme.kt:99`, `seed == null`) | ⚠️ | B |
| defaultThemeColor | SeedSwatchRow (`:114`) | only the settings screen itself (`:69`) | never reaches SonoraTheme | ❌ | B |
| amoledDark | Switch `:123` | `MainActivity.kt:39` | immediate | ✅ | B |
| blurredBackgrounds | Switch `:129` | `nowplaying/NowPlayingViewModel.kt:44` → `NowPlayingOverlay.kt:61` | next frame | 🚧 | B |
| nowPlayingStyle | ChoiceRow `:143` | `NowPlayingViewModel.kt:41` | immediate | 🚧 | B |
| preferLocalArtistImages | Switch `:153` | none; `ArtistsTab.kt:110`, `ArtistRoute.kt:154`, `FavoritesRoute.kt:382`, `StatsRoute.kt:206` always prefer `localImagePath` | toggle has no effect | ❌ | B |
| showVisualizer | Switch `:160` | `NowPlayingViewModel.kt:47` → `NowPlayingOverlay.kt:113` | immediate | 🚧 | B |
| immersiveMode | (Now playing toggle) | `NowPlayingViewModel.kt:38`/`:74` | immediate | ✅ | B |
| useGreetingTitle / userName | Switch `:169`, name dialog `:177`; onboarding | `home/HomeViewModel.kt:84` | immediate | ✅ | B |
| appLocale | LanguageDialog (`SettingsRoute.kt:211`) | `locale/AppLocales.kt:16` (AppCompat) | activity recreate | ✅ | B |
| defaultStartPage | ChoiceRow (Playback › Startup) | `MainActivity.kt:44` | 🕑 next cold start / new shell | 🕑 | B |

## Formatting (`ui/screens/settings/formatting/SettingsFormattingRoute.kt`)

| Setting | Runtime reader | Effect | Mark | Bak |
|---|---|---|---|---|
| filterTitleFeatures | preview only (`:47`); `playback/QueueController.kt:46` hard-codes `{ it.title }`; SongRow etc. use raw title | no effect outside the preview | ❌ | B |
| filterTitleArtist | same | same | ❌ | B |

## Playback (`ui/screens/settings/playback/SettingsPlaybackRoute.kt`)

| Setting | Runtime reader | Effect / timing | Mark | Bak |
|---|---|---|---|---|
| replayGainMode | `playback/SonoraPlaybackService.kt:181` | next item transition (current item not re-gained) | 🕑 | B |
| keepPlayingOnClose | `SonoraPlaybackService.kt:215` (`onTaskRemoved`) | at swipe-away | ✅ | B |
| restoreLastPlayedSong | `SonoraPlaybackService.kt:144` | 🕑 next service start | 🕑 | B |
| pauseOnDuck | `playback/AudioFocusController.kt:68` | immediate (re-registers focus) | ✅ | B |
| pauseOnDisconnect | `playback/NoisyAndReconnectHandler.kt:44` | next disconnect | ✅ | B |
| resumeOnConnect | `NoisyAndReconnectHandler.kt:52` | next reconnect | ✅ | B |
| bluetoothMfxPatch | `playback/AudioEffects.kt:116` | immediate EQ recompute | ✅ | B |
| sleepTimerDefaultMinutes | none; `nowplaying/sheets/SleepTimerSheet.kt:71` starts with `selected = null` | no preselection | ❌ | B |
| sleepTimerFadeOutSeconds | `SleepTimerSheet.kt:76`; `SonoraPlaybackService.kt:384` (extend) | 🕑 next sheet open | 🕑 | B |
| sleepTimerFinishSong | `SleepTimerSheet.kt:75`; `SonoraPlaybackService.kt:384` | 🕑 next sheet open (behaviour being changed by another agent) | 🕑 | B |

## Not on a settings screen (set from player / lists / system)

| Setting | Writer → reader | Mark | Bak |
|---|---|---|---|
| repeatMode | player → `SonoraPlaybackService.kt:179`, `:468` | ✅ | – (Flutter has none) |
| volume | player → `SonoraPlaybackService.kt:178`, `PlaybackConnectionImpl.kt:182` | ✅ | B |
| speed, mfxPreset | MFX sheet → `AudioEffects.kt:101` | ✅ | B |
| mfxReverb/Lofi/BassBoost/CustomEq, mfxCustomEqGains | MFX sheet → `AudioEffects.kt:109-116`, `SonoraPlaybackService.kt:332/374` | ✅ | B |
| song/album/artist/playlist sort | Home sort menu → `home/HomeViewModel.kt:177-182` | ✅ | B |
| favoritesSortBy/Ascending | **no writer, no reader**: `favorites/FavoritesViewModel.kt:41` keeps a local `MutableStateFlow(FavSort.Date to false)` | ❌ (not persisted) | B |
| songActivityView | **no writer, no reader**: `HomeViewModel.kt:100` derives view from the Plays sort direction | ❌ | B |
| autoCheckUpdates | Info switch `:115` → `update/UpdateScheduler.kt:21` | 🕑 next scheduled check | B |
| updateChannel | updates screen → `update/UpdateViewModel.kt:96/124` | ✅ next check | B |
| lastUpdateCheckMs, updateRateLimitResetMs | `UpdateScheduler.kt:22` | ✅ (internal) | – |
| scanFolderPath/Uri, onboardingCompleted | onboarding → `gate/GateHost.kt:89`, `repo/impl/LibrarySyncPorts.kt:34` | ✅ | – |
| lastSync*, postponeSyncUntilMs | `LibrarySyncPorts.kt:35-36` | ✅ | – |
| colorlessArtworkPaths | `LibrarySyncPorts.kt:37` | ✅ | – |
| useMaterialYou / useDynamicTheme | legacy mirrors, read only to derive `themeColorSource` | ✅ | B |

## Counts

✅ 30 · 🕑 7 · ❌ 7 · ⚠️ 1 · 🚧 3 (rows above; grouped rows counted per field).
