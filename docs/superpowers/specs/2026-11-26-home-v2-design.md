# Home v2 — design (addendum to the Home screen design)

## Requests
- Remove the Library button (Home toolbar), the Home icon (Library header) and the "Library" footer button. Keep
  swipe-up, the four tiles, the bottom bar and Back.
- Home toolbar title is the app name (`R.string.app_name`). The greeting sits below it in full length (wrapping, no
  ellipsis, light weight); the name is underlined and tappable and opens a quick-edit dialog. The name row and the
  greeting switch are removed from settings (default name "User"; keep the `user_name` backup key).
- More personal Home: Shuffle-all CTA (starts shuffled playback of the whole library, no navigation), Scan library
  CTA, stats at a glance, favourite-song card, and a now-playing card: synced lyrics over the dimmed cover while a
  song plays (only when synced lyrics exist, otherwise undimmed cover); tap = same as tapping the mini player.

## Risks / rules (from review)
- Lint is `abortOnError`, MissingTranslation is an error: every new string in values/, values-ja/, values-tr/.
  Leave existing unused strings (`home_action_home`, `home_action_library`, `home_open_library`, `settings_name*`).
- Greeting strings place `%1$s` differently per language: format with a marker char, split, build an
  `AnnotatedString` with `LinkAnnotation.Clickable` + `TextDecoration.Underline` (see `changelog/SleeveMarkdown.kt`).
- Name: Home reads `settings.userName` directly and always shows the greeting. Dialog: reuse `TextInputDialog`
  (`ui/sheets/PlaylistDialogs.kt`); save `trim().ifEmpty { "User" }` and `setUseGreetingTitle(true)`.
- Scan: launch `LibraryRepository.sync()` from `HomeViewModel.scanLibrary()` in `viewModelScope`; progress from
  `isSyncing` + background tasks; disable the button while scanning.
- Live lyrics: pass `positionMs: () -> Long` (read the State lazily); active line via `derivedStateOf`; load once per
  `song.id` with `produceState` + `LyricsLocator.load(filePath)` on IO as `LyricsView.kt` does; synced lyrics only;
  draw ~3 lines, no LazyColumn, `Crossfade`. Do not change `LyricsView.kt`.
- Stats: no listening time stored; estimate plays × duration and label it approximate.

## Workstreams (disjoint files)
- **W1** owns `HomeDashboard.kt`, `HomeDashboardViewModel.kt`, `HomeRoute.kt`, `HomeViewModel.kt`, `HomeHeader.kt`,
  `HomeScreen.kt`, `SonoraAppShell.kt`, `SettingsAppearanceRoute.kt`, new `home/NameEditDialog.kt`, `strings_home3.xml`.
  Adds `onOpenNowPlaying: () -> Unit` to `HomeRoute`/`HomeDashboard` (pass `openNowPlaying` at both shell call
  sites), `HomeViewModel.scanLibrary()`, `HomeDashboardViewModel.glance: StateFlow<HomeGlance?>`.
  Card order: header; `NowPlayingLyricsCard` if a song is playing else `ContinueCard`; `Row { ShuffleAllCta;
  ScanLibraryCta }`; `StatsGlanceCard`; `FavoriteSongCard`; tiles; Recommended; Weekly; Recent. Shuffle:
  `scope.launch { playback.playShuffled(library.songs.first()) }`, no navigation.
- **W2** owns new files in `dashboard/` + `strings_home_cards.xml`, plus
  `shared/.../stats/HomeGlance.kt` (`data class HomeGlance(songCount: Int, plays: Int, listenedMs: Long,
  topArtist: String?, favorite: Song?, favoritePlays: Int)`, `companion fun from(s: StatsSnapshot, songs: List<Song>)`;
  favorite = `StatsAggregates.topSongs(s, songs, 1)` else first `isFavorite`) and `HomeGlanceTest`.
  Composables: `ShuffleAllCta(enabled, onShuffle, modifier)`, `ScanLibraryCta(isSyncing, tasks: List<BackgroundWork>,
  onScan, modifier)`, `StatsGlanceCard(glance, onOpenStats, modifier)`, `FavoriteSongCard(song, plays, onPlay, modifier)`.
- **W3** owns `dashboard/NowPlayingLyricsCard.kt` + `strings_home_np.xml`:
  `NowPlayingLyricsCard(song: Song, positionMs: () -> Long, onOpen: () -> Unit, modifier: Modifier = Modifier)`;
  `AlbumArt` background, ~0.55 black dim only when synced lyrics exist, whole card clickable, content description
  "Now playing: title, artist".
