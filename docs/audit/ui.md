# Sonora — UI Screens, Widgets & Navigation Audit

Scope: `lib/app.dart`, `lib/routing/*`, all non-settings `lib/screens/*.dart`, all `lib/widgets/**`, and the tests
`widget_test`, `batch_actions_test`, `dialogs_and_sheets_test`, `sync_prompt_banner_test`,
`large_playlist_simulation_test`. Focus: behavior, content, information architecture (not pixel styling).

Conventions: `l10n:key` = key in `lib/l10n/app_en.arb` (English value quoted when useful).
**HARDCODED** = English literal not in l10n (must be moved into `strings.xml` when porting).
⚠ = quirk / bug / porting risk.

---

## 0. Global app shell (`lib/app.dart`)

### 0.1 Startup gate state machine
`_SonoraAppState` owns gate flags and pushes them to the router (`updateGateState` + a refresh notifier). Router
`redirect` priority (first match wins):

1. `_hasError` → `/error`
2. `_isLoading` → `/loading`
3. `_showOnboarding` → `/onboarding`
4. `!_hasPermission` → `/permission`
5. If currently on any gate path (`/loading`, `/onboarding`, `/permission`, `/error`) → `/home`
6. Otherwise no redirect.

Startup sequence `_loadSongs()` → `_loadSongsCore()`:
- Starts a **15 s warning timer** (loading screen shows "Loading is taking longer than expected…" — HARDCODED, red)
  and a **30 s hard timeout** (→ error screen). Any exception → error screen.
- Loads settings. If pref `onboarding_completed` is false → onboarding.
- Requests all permissions (`PermissionService.requestAllPermissions`). Denied → permission screen.
- If no scan folder configured → onboarding (again, even if onboarding_completed).
- Loads cached library (`scanAllSongs`) + playlists.
- **Sync reminder rule**: `showSyncPrompt = now >= postpone_sync_until (default 0) && last_sync_timestamp != null &&
  last_sync_timestamp < now − 30 days`. (No prompt if the app has never synced.)
- Populates provider (`updatePlaylists`, `updateSongs`), goes to home.

### 0.2 Gate screens (all built inline in app.dart, all text HARDCODED)
| Route | Content | Actions |
|---|---|---|
| `/loading` | Centered spinner; after 15 s adds warning text "Loading is taking longer than expected…" | none |
| `/error` | Error icon, "Sonora failed to load", explanation "The app got stuck during startup. This can happen when background processes prevent a clean restart.", footer hint "Try force-closing first. If the problem persists, clear app data to reset." | **Force Close App** (`SystemNavigator.pop`), **Clear App Data & Retry** (clears SharedPreferences with 3 s timeout, resets songs/folder, reruns `_loadSongs`) |
| `/permission` | Audio-file icon, "Access to Music Files", "To play local audio files, Sonora requires permission to access your device storage." | **Grant Permission** → reruns `_loadSongs` (which re-requests permissions) |

### 0.3 App-level callbacks & their snackbars (global `ScaffoldMessenger` key)
- `_completeOnboarding(folder, userName)`: sets userName, **forces `useGreetingTitle = true`**, sets
  `onboarding_completed`. If folder given: shows loading, saves folder, `importFromFolder`, rescans, then snackbar
  (HARDCODED): "Sync folder configured. Imported N new song(s) (X unique themes pre-computed)!" or
  "Sync folder configured successfully (X unique themes pre-computed)!".
- `_configureScanFolder()` (from Songs-tab empty state & settings): Android → system directory picker;
  iOS → `pickAudioFilesIntoLibrary` (copies files into app sandbox). Same snackbar as above.
- `_resetApp()`: clears all prefs, stops playback, empties library, clears image cache, goes to onboarding,
  snackbar "Library and settings cleared successfully!" (HARDCODED).
- `_syncSongsSilently()` (resync): guarded by `_isSyncing`; `scanner.syncLibrary()`, refresh sync stats, update songs.
  Errors swallowed.
- `_onResyncNow()`: hides prompt, runs silent sync.
- `_onPostponeSync()`: writes `postpone_sync_until = now + 30 days`, hides prompt, snackbar
  "Sync reminder postponed for 1 month." (HARDCODED).
- Playlist CRUD wrappers: create/rename/delete/add song/remove song/reorder (reorder is **optimistic**: updates
  provider first, then persists).

### 0.4 Theming hooks relevant to behavior
Seed color: `ThemeColorSource.materialYou` → wallpaper color (fallback default), `albumArt` → current song's
extracted color (`themeColorNotifier`), `custom` → default theme color. Light/dark/system via ThemeProvider;
AMOLED dark option. Locale from settings. A root `GestureDetector` unfocuses the keyboard on any tap outside.

---

## 1. Navigation map

### 1.1 Routes (`lib/routing/app_routes.dart`, go_router)
| Path | Params | Screen | Notes |
|---|---|---|---|
| `/loading` | – | Loading gate | initialLocation |
| `/error` | – | Error gate | |
| `/onboarding` | – | `OnboardingScreen` | |
| `/permission` | – | Permission gate | |
| `/home` | – | `HomeScreen` | inside shell |
| `/settings` + `/settings/{appearance,playback,formatting,info,privacy,backup}` | – | settings screens | out of my scope |
| `/album` | query `artist`, `album` | `AlbumDetailScreen` | rebuilt from `allSongs` via `buildAlbumGroup`; errors "Missing album data" / "Album not found" (HARDCODED, bare Scaffold) |
| `/artist` | query `name` | `ArtistDetailScreen` | `buildArtistGroup` (+ local artist images if `preferLocalArtistImages`); "Missing artist data" / "Artist not found" |
| `/playlist` | query `id` | `PlaylistDetailScreen` | looks up provider playlists; "Missing playlist data" / "Playlist not found" |
| `/stats` | – | `StatsScreen` | entered only from Settings → `l10n:viewStatistics` |
| `/recently-played` | – | `RecentlyPlayedScreen` | from Home app bar & Stats overview card |
| `/favorites` | – | `FavoritesScreen` | from Home app bar |
| `/changelog` | – | `ChangelogScreen` | from Info settings |

Navigation helpers (`app_navigation.dart`): `goToHome` (go), `openSettings`, `openAlbum`, `openArtist`,
`openPlaylist`, `openStats` (unused), `openRecentlyPlayed`, `openFavorites` (all `push`), and `closeRoute`
(first `Navigator.maybePop()` to dismiss any modal on the local navigator, else `context.pop()`).

⚠ `QueueScreen` (`lib/screens/queue_screen.dart`) is **not routed anywhere** — dead code kept alive by tests. Its
features are duplicated in Now Playing → Up Next. Don't port as a separate screen (or decide deliberately).

### 1.2 Shell structure
All non-gate routes live under one `ShellRoute` whose builder is a `Scaffold(extendBody: true)` with
`bottomNavigationBar = _ShellMiniPlayer` **only when `playerProvider.currentSong != null`**. There is no bottom
navigation bar / tabs at shell level; Home's tabs are internal to HomeScreen. Every pushed screen (detail, stats,
favorites, settings…) shows the mini player underneath. Screens pad list bottoms by ~100–140 dp to clear it.

### 1.3 Now Playing presentation
- Opened by tapping the mini player or swiping it up → `showModalBottomSheet` on the **shell navigator**
  (`useRootNavigator: false`), `isScrollControlled`, `useSafeArea`, rounded top (24). So it is a full-height
  modal sheet over the current page; drag-down dismisses; Android back dismisses the sheet before popping routes.
- Guard `_isNowPlayingOpen` prevents stacking duplicate sheets.
- The sheet auto-closes itself (`closeRoute` post-frame) if `currentSong` becomes null (e.g. stop).

### 1.4 Modal sheets stacked over Now Playing
- Tapping the **artist** text: if multiple artists (`parseIndividualArtists`) → bottom sheet listing artists
  (`l10n:artists` title, avatar, name, "N songs" built as `'${count} ${l10n:songs}'`); tapping one opens
  `ArtistDetailScreen` **as another modal bottom sheet** (not a route). Single artist → opens directly.
- Tapping the **album** text → `AlbumDetailScreen` as a modal bottom sheet.
- ⚠ Inside these sheets, the screens' back button calls `closeRoute`, which pops the sheet. Detail screens thus
  exist in two hosting modes (route & sheet). In the port, decide one (recommend: collapse NP + navigate to route).

### 1.5 Other imperative navigation
⚠ `ArtistDetailScreen` album carousel uses `Navigator.push(MaterialPageRoute(AlbumDetailScreen))` — bypasses
go_router (no URL, no shell awareness when inside the NP sheet).

### 1.6 Back behavior summary
- Screens with multi-select (Home, Album, Artist, Playlist, Favorites, Recently Played) wrap in `PopScope(canPop:
  selection.isEmpty)`; first back clears selection, second back pops.
- Detail screens use explicit back arrow → `closeRoute`.
- Now Playing: leading button is "collapse" (down chevron, tooltip `l10n:collapse`) in player view → closes sheet;
  in Up Next/Lyrics/Related views it is a back arrow (tooltip `l10n:back`) → returns to player view.
  ⚠ System back has no PopScope here → it closes the whole sheet even when in a sub-view.
- Onboarding pages: swipe disabled (`NeverScrollableScrollPhysics`), no back button between steps.

### 1.7 Deep links
None. AndroidManifest has only LAUNCHER, audio_service, media button, home-screen widget, notifications boot
receivers, FileProvider. No URL schemes in iOS. Routes use query params but are never entered externally.

### 1.8 Transitions / animations
- Default Material page transitions for go_router pushes.
- Home tab content: `PageTransitionSwitcher` + `SharedAxisTransition` horizontal, direction by tab index delta.
- Mini player and Now Playing artwork: SharedAxis horizontal on song change; direction reversed when the new
  `currentIndex` < previous (i.e. went back).
- `Hero` tags exist only on destination side (`album_art_<albumName>` in AlbumDetail, `playlist_art_<id>` in
  PlaylistDetail) ⚠ — no matching source Hero in lists, so no shared-element transition actually happens.
- Now Playing content views switch with `AnimatedSwitcher` (300 ms).

---

## 2. Home screen (`lib/screens/home_screen.dart` + `lib/widgets/home/*`)

### 2.1 Structure
`NestedScrollView` with a `SliverAppBar(floating: true, pinned: true)` (collapsing/floating header), body =
search/filter header + tab content wrapped in `CustomScrollbar` (fast-scroll). Multi-select action bar overlays
the bottom when a selection exists.

**Title** (reactive to settings):
- If `useGreetingTitle` (default false; forced true after onboarding): time-based greeting with user name —
  hour < 12 `l10n:goodMorning` ("Good morning, {name}"), < 17 `l10n:goodAfternoon`, else `l10n:goodEvening`.
- Else: `l10n:appTitle` + headphones icon.
- Dev builds append a "DEV" badge (`DevBadge`, HARDCODED "DEV"; detected via `NativeBridge.getPackageInfo().isDevBuild`).

**App bar actions** (each unfocuses search first):
1. History icon → `/recently-played` (tooltip `l10n:recentlyPlayed`)
2. Heart icon → `/favorites` (tooltip `l10n:favorites`)
3. Gear icon → `/settings` (tooltip `l10n:settings`)

**App bar bottom**: when syncing (`_isSyncing` local or `widget.isSyncing`), a 2 dp indeterminate
`LinearProgressIndicator`; then a pill-shaped `TabBar` with 4 tabs: `l10n:songs`, `l10n:albums`, `l10n:artists`,
`l10n:playlists`. Initial tab = `settings.defaultStartPage`. Tapping the already-selected tab scrolls the
NestedScrollView to top (300 ms). Changing tabs clears any song selection. Tabs not swipeable (content switch only
via tab bar).

**On open**: Android release builds with `autoCheckUpdates` → `UpdateService.checkForUpdate()`; if update →
`UpdateDialog` (§9.8).

### 2.2 Search & filter header (per tab)
Row: search `TextField` (pill, prefix search icon, clear "×" suffix when non-empty → clears + unfocuses), then
action buttons **hidden while the search field has focus**:

| Tab | Hint | Button 1 | Button 2 | Sort | Count label below |
|---|---|---|---|---|---|
| Songs (0) | `l10n:searchSongsHint` | Shuffle (`l10n:shufflePlay`) → `quickShuffle(filteredSongs)` | – | sort sheet | `l10n:songCount(n)` + " • " + total duration (`formatTotalDuration`) |
| Albums (1) | `l10n:searchAlbumsHint` | Shuffle → quickShuffle(all songs of filtered albums) | – | sort sheet | `l10n:albumCount(n)` |
| Artists (2) | `l10n:searchArtistsHint` | Shuffle → quickShuffle(all songs of filtered artists) | – | sort sheet | `l10n:artistCount(n)` |
| Playlists (3) | `l10n:searchPlaylistsHint` | Import M3U (`l10n:importM3u`, download icon) | Create playlist (`l10n:createPlaylist`) | sort sheet | `l10n:playlistCount(n)` |

- One shared query string across all tabs (persists when switching tabs). Trimmed.
- Songs/Albums/Artists filter via `LibrarySearchIndex` (search + sort in one call); Playlists filter by
  `nameLower.contains(query)` and sorted locally.
- Import M3U: generic file picker; only acts if extension `.m3u`/`.m3u8` (silently ignores others) →
  `playerProvider.importM3u(file)` → snackbar `l10n:importedPlaylist` ("Playlist imported").
- Sort button is highlighted (primary fill) when Songs tab sort = `plays`.
- `quickShuffle`: shuffles a copy, starts first item, records a shuffle session, queue marked shuffled.

### 2.3 Sort bottom sheet (`widgets/home/sort_bottom_sheet.dart`)
Root-navigator modal, drag handle, max 85% height. Title per tab: `l10n:sortSongsBy` / `sortAlbumsBy` /
`sortArtistsBy` / `sortPlaylistsBy`. Option list (single select, check mark) + section label `l10n:sort` +
2-segment direction control. Selections apply immediately (sheet stays open), persisted via
`SettingsProvider.saveSortSettings`, haptic selection click.

| Tab | Options (label key → value) | Direction segments |
|---|---|---|
| Songs | `sortByTitle`→title, `sortByArtist`→artist, `sortByDuration`→duration, `sortByRecentlyAdded`→recent, `mostPlayed`/`leastPlayed`→plays (label & icon flip with current direction) | normally `sortAscending`/`sortDescending`; when `plays` selected: `mostPlayed` (asc=false) / `leastPlayed` (asc=true) |
| Albums | `sortByAlbumName`→name, `sortByArtist`→artist, `sortByTrackCount`→tracks, `sortByRecentlyAdded`→recent | asc/desc |
| Artists | `sortByArtistName`→name, `sortByAlbumCount`→albums, `sortBySongCount`→songs | asc/desc |
| Playlists | `sortByPlaylistName`→name, `sortBySongCount`→songs (counts only IDs present in library; ties by name) | asc/desc |

Songs `plays` special-casing: switching *into* plays sets descending (most played first); switching *out of*
plays resets ascending. With plays, search is done by title then reordered by `statsService.applyActivityView`
(`SongActivityView.mostPlayed` / `leastPlayed`), and each song row shows metadata `l10n:listensCount`
("Never played"/"1 listen"/"{n} listens"). The activity view is also written to settings.

Defaults: songs title asc, albums/artists/playlists name asc.

### 2.4 Fast scrollbar (`CustomScrollbar`)
Custom draggable thumb on the right; fades in on scroll, hides 1.5 s after. Dragging shows a floating section
bubble with text from `_getScrollSection(percentage)`:
- Songs: first letter A–Z else `#` of title; if sort=artist → artist letter; sort=duration → "{minutes}m";
  sort=plays → play count.
- Albums: album name letter (or artist letter if sorted by artist).
- Artists / Playlists: name letter.
Haptic selection click each time the section changes.

### 2.5 Songs tab (`widgets/home/songs_tab.dart`)
- **Empty library** (`allSongs.isEmpty`): icon (folder if no scan folder, music-off otherwise), title
  `l10n:setMusicDirectory` (no folder) or `l10n:noMusicFilesFound`; subtitle iOS `l10n:addMusicHint`, else
  `l10n:chooseFolderSubtitle` or `l10n:noMusicFilesInFolderSubtitle(folder)`; button iOS `l10n:addMusic`, else
  `l10n:setMusicDirectory` / `l10n:change` → `onConfigureFolder`.
- **Sync banner** at top (only when library non-empty and `showSyncPrompt`), see §2.9.
- **No search results**: search-off icon + `l10n:noMatchingSongsFound` (or, if activity view = recentlyPlayed,
  history icon + `l10n:noRecentlyPlayedSongs` + `l10n:noRecentlyPlayedSongsSubtitle` — that view is not
  reachable from the current sort sheet, legacy).
- List of `SongTile`s (§3). Tap → `playSong(song, filteredSongs)` (queue = current filtered+sorted list).
  Long-press → enters multi-select. Current song highlighted.
- No pull-to-refresh anywhere in the app.

### 2.6 Albums tab
2-column grid (aspect 0.78). Card: artwork (first song's art), album name, artist, `l10n:trackCount(n)`. Tap →
`/album`. Empty library: `l10n:noAlbumsFound`; no matches: `l10n:noMatchingAlbumsFound`. No long-press/menu.

### 2.7 Artists tab
List: avatar (`ArtistAvatar`: local artist image if exists → first available song artwork → person icon), name,
"`albumCount` • `songCount`", chevron. Tap → `/artist`. Empty: `l10n:noArtistsFound`; no matches:
`l10n:noMatchingArtistsFound`. No menu.

### 2.8 Playlists tab
- Empty (no playlists at all): icon, `l10n:noPlaylistsYet`, `l10n:noPlaylistsYetSubtitle`, button
  `l10n:createPlaylist` → Create dialog. No matches: `l10n:noMatchingPlaylistsFound`.
- Row: cover (custom cover file → first existing song's artwork → gradient + note icon), name, `l10n:songCount`
  (counts only songs still in library). Tap → `/playlist?id=`.
- **Overflow menu (⋮) items in order**:
  1. `l10n:exportToM3u` → `exportPlaylistToM3u`; success → system share sheet with text `l10n:exportedPlaylist(name)`;
     failure → snackbar `l10n:failedToExport`.
  2. `l10n:editDescription` → Edit Description dialog (§9.3).
  3. `l10n:changeCover` → snackbar `l10n:chooseSquareImage` (2 s), image picker, crop/downscale to
     `<appDocs>/playlist_covers/<id>.jpg` (`PlaylistImageUtils.processAndSavePlaylistCover`), evict image cache,
     `updatePlaylistCover`.
  4. `l10n:removeCover` (only if a cover is set) → `updatePlaylistCover(id, null)`.
  5. `l10n:rename` → Rename dialog (§9.2).
  6. `l10n:delete` (red) → Confirm dialog `l10n:deletePlaylistConfirmTitle` / `deletePlaylistConfirmMessage(name)` →
     delete → snackbar `l10n:playlistDeleted(name)`.

### 2.9 Sync prompt banner (`widgets/home/sync_prompt_banner.dart`)
Card with sync-problem icon, title `l10n:syncLibraryDatabase` ("Sync Library Database?"), body
`l10n:syncLibraryDatabaseSubtitle`, buttons:
- **"Remind Next Month"** (HARDCODED, tested by literal text) → HomeScreen hides banner immediately, calls
  `onPostponeSync` (30-day snooze + snackbar, §0.3).
- **`l10n:syncNow`** → hides banner immediately, sets local `_isSyncing` (progress bar in app bar), awaits resync,
  then snackbar `l10n:syncedXSongs(count, durationText)` where durationText = " in {lastSyncDuration}ms"
  (HARDCODED English fragment) or "".
- Both disabled while `isSyncing`.
Display rules: shown on **Songs tab only**, only if library non-empty; computed once at startup (monthly = 30 days
since `last_sync_timestamp`, not if snoozed, never if no sync timestamp). Tests (`sync_prompt_banner_test`) assert:
banner renders/hides by flag; tap Remind → postpone callback + banner gone; tap Sync Now → callback, banner gone,
LinearProgressIndicator visible until completion.

### 2.10 Multi-select mode (Home songs tab and other song lists)
- **Entry**: long-press a song tile (haptic medium) → that song selected. Only the Songs tab supports it on Home.
- While selecting: tile tap & long-press both toggle selection (haptic selection click); artwork shows an overlay
  check-circle / empty-circle; selected rows tinted + left accent bar; overflow menu hidden; drag handles hidden.
- **Exit**: close (×) button, select-all toggle when everything selected ("Deselect all"), system back, any
  action executed, or (Home) tab change.
- **Action bar** (`MultiSelectActionBar`, floating card above mini player — bottom padding 80 if mini player
  visible else 16):
  - Top row: close (tooltip `l10n:close`), count pill `l10n:selectedCount` ("{n} selected"), right side toggle
    `l10n:selectAll` / `l10n:deselectAll` (select-all = all songs in the current filtered list).
  - Horizontally scrollable actions (all haptic medium, all clear selection):
    1. `l10n:playNext` → `playNextSongs(selected)` → snackbar `l10n:batchAddedToPlayNext(n)`.
    2. `l10n:addToQueue` → `addSongsToQueue` → snackbar `l10n:batchAddedToQueue(n)`.
    3. `l10n:addToPlaylist` → Playlist selector (multi mode, §9.1).
    4. Favorite toggle: if all selected are favorites → `l10n:favoriteRemove` (unfavorite all), else
       `l10n:favoriteSong` (favorite all) → `setFavorites(ids, state)` → snackbar `l10n:batchFavorited(n)` /
       `batchUnfavorited(n)`.
    5. `l10n:share` → system share of file paths, share text "Sharing N songs" (HARDCODED).
    6. Only in Playlist detail: `l10n:removeFromPlaylist` (error color) → `removeSongsFromPlaylist` → snackbar
       `l10n:batchRemovedFromPlaylist(n, playlistName)`.
  - Snackbars: floating, 2 s.
- Test `batch_actions_test` asserts "1 selected", select-all icon, close icon, and the 5 action icons.

---

## 3. Song tile & song context menu (`widgets/song_tile.dart`)

Row: artwork 48 dp (selection overlay in select mode), title (`song.displayTitle` — feature/artist cleanup per
formatting settings), subtitle `artist` or `artist • metadataLabel`, duration (`durationFormatted`), optional ⋮
menu. Optional leading drag handle (playlist detail). Current song: primary-colored text + tinted background +
left accent bar (background can be disabled via `showHighlightBackground: false`).

**Context menu items** (shown when not selecting, `hideMenu` false, and provider given), in order:
1. `l10n:playNext` → `playNext(song)`
2. `l10n:addToQueue` → `addToQueue(song)`
3. `l10n:addToPlaylist` → Playlist selector (single mode)
4. `l10n:removeFromPlaylist` (only when `onRemoveFromPlaylist` provided, i.e. Playlist detail)
5. `l10n:songInfo` → Song info sheet (§8)
6. Favorite toggle: `l10n:favoriteRemove` (filled red heart) / `l10n:favoriteSong` — state read live from
   `allSongs` at menu open time.
No snackbars for single play next / add to queue / favorite.

Tiles with `hideMenu: true`: Now Playing Related list, Up Next/Queue, Stats top songs.

---

## 4. Mini player (`widgets/mini_player.dart`, shell `_ShellMiniPlayer`)

- Visible on every shell route whenever a current song exists. Floating rounded card (72 dp) inside SafeArea with
  12 dp margin; text scale clamped 1.0–1.3.
- **Progress strip**: 6 dp bar across the top edge (inset 12 dp), fill = position/duration from
  `positionStream`, gradient primary→tertiary (end color lerps with progress).
- Content: artwork 44 dp, title (`displayTitle`), artist; SharedAxis horizontal animation on song change
  (reverse when index decreased).
- Buttons: Play/Pause (icon: replay if `isCompleted`, pause if playing, play otherwise) → `playPause`;
  Next → `next`. No previous button.
- **Gestures** (velocity threshold 100 px/s, on drag end):
  - Tap → open Now Playing sheet.
  - Swipe **up** → open Now Playing.
  - Swipe **down** → `playerProvider.stop()` — stops playback, **clears queue**, resets index, clears last-played
    state → mini player disappears. ⚠ destructive, no confirmation/undo.
  - Swipe **left** (finger moves left, velocity < −100) → `next()`.
  - Swipe **right** (finger moves right) → `previous()` (which restarts if position > 3 s per audio handler, and
    records a "restart" stat).
  ⚠ Naming in code is inverted (`onSwipeRight` is invoked for a leftward swipe) — port by *direction of finger*:
  left = next, right = previous.

---

## 5. Now Playing (`screens/now_playing_screen.dart`)

### 5.1 Chrome
- Transparent `AppBar` over content: leading collapse/back (§1.6), title by view: `l10n:nowPlaying`,
  `l10n:upNext`, `l10n:lyrics`, `l10n:related`.
- Actions: in Up Next view only, "save as playlist" icon (tooltip `l10n:saveAsPlaylist`) → Save Queue dialog
  (§9.4). Always: ⋮ menu with single item `l10n:songInfo` → Song info sheet.
- Background: if `blurredBackgrounds` setting on and song has art → `BlurredCoverBackground` (tiny 32 px decode
  stretched); else primary→tertiary soft gradient. Plus a dark (dark theme) / surface (light) vertical scrim.
- Optional **visualizer** (`settings.showVisualizer`, default off): 80 dp tall, 20 bars, pinned to the bottom,
  non-interactive. ⚠ It is a **fake** sine-wave animation (not audio-reactive), amplitude eases to zero on pause.

### 5.2 Views (`_ViewMode`: player / upNext / lyrics / related)
Bottom bar of 3 equal text tabs: `l10n:upNextCaps`, `l10n:lyricsCaps`, `l10n:relatedCaps`. Tapping the active one
returns to player view. Disabled (dimmed) when not applicable, tooltip explains: Lyrics disabled when
`!song.hasLyrics` (tooltip `l10n:noLyricsAvailable`); Related disabled when no other song shares artist OR album
(tooltip `l10n:noRelatedSongs`). Up Next always enabled.

### 5.3 Player view (scrollable column)
1. **Artwork area** (gestures on the whole area):
   - Tap → toggles `settings.immersiveMode` (persisted): art expands to full available width/size.
   - Horizontal swipe (|v|>100): left → `next()`, right → `previous()`.
   - Layout by `settings.nowPlayingStyle`:
     - `modern` (default): rounded square art, ~80% width max 300 dp (immersive → full width); SharedAxis
       transition on song change.
     - `vinyl`: `AnimatedVinyl` — black record with grooves, circular art label (45%), spindle hole; rotates once
       per 12 s while playing, stops (holds angle) when paused; size min(75% width, 270) or full in immersive.
       When the in-art lyrics overlay is on, vinyl falls back to the square layout.
     - `minimalist`: smaller square art ~55% width max 200 dp (ignores immersive).
   - **In-art lyrics overlay**: when the Lyrics chip is active, `SongLyricsOverlay` is drawn over the square art
     with a blur (or opaque scrim if blurred backgrounds off). Auto-disabled when the song has no lyrics.
2. **Title/artist/album row**: `MarqueeText` title (auto-scrolls if overflowing: 3 s delay, 30 px/s, 3 s pause,
   loops with gap); artist (tap → artist sheet, §1.4) • album (tap → album sheet). Trailing
   `AnimatedFavoriteButton` (heart, red when fav, pop scale animation when favoriting; tooltips
   `l10n:addToFavorites` / `l10n:removeFromFavorites`) → `toggleFavorite(song.id)`.
3. **Action chips row** (horizontal scroll):
   - `l10n:lyrics` (only if song has lyrics) — toggles in-art lyrics overlay (active state highlighted).
   - `l10n:playlists` → Playlist selector for current song.
   - Timer chip: inactive `l10n:timer`; active shows `l10n:stopInX(m:ss)` or `l10n:sleepTimerFinishingSong` →
     Sleep timer sheet (§9.5).
   - `l10n:mfx` (sparkle icon, active when any MFX effect on) → MFX sheet (§9.6).
   - Volume chip showing "{volume%}" → toggles inline `VolumeSlider` (default shown only on Windows).
4. **SeekBar** (§5.6).
5. **VolumeSlider** if toggled: 0–1, 100 divisions, icon changes off/mute/down/up by level → `setVolume`.
6. **PlayerControls**: Shuffle (icon `shuffle_on` when on; tooltips `l10n:enableShuffle`/`disableShuffle`),
   Previous (`l10n:previousTooltip`), big Play/Pause (replay icon when completed; `l10n:playTooltip`/`pauseTooltip`),
   Next (`l10n:nextTooltip`), Repeat cycling **off → all → one → off** (icons repeat / repeat_on / repeat_one_on;
   tooltips `l10n:repeatOff`/`repeatAll`/`repeatOne`).

### 5.4 Up Next view (`widgets/up_next_queue_view.dart`)
- Empty queue: icon + `l10n:queueEmpty`.
- Header: `l10n:queueNOfM(current+1, total)` ("Queue ({current} of {total})") or `l10n:queue`; "scroll to current"
  button (tooltip `l10n:scrollToCurrentTrack`, light haptic) → scrolls to top.
- List starts at the **previous** track (`currentIndex − 1`) so the current one is 2nd; earlier items hidden.
  Each row: drag handle (only the handle starts a drag), absolute 1-based queue number (tabular), `SongTile`
  (no menu). Current row: tinted, accent bar, bold primary number. Played rows (index < current) at 35% opacity.
- Tap a non-current row → `audioHandler.skipToQueueItem(index)`.
- Drag reorder → `reorderQueue(old+offset, new+offset)`.
- Swipe end-to-start (left) → remove (`removeFromQueue` by first index with same song id ⚠ duplicates of the same
  song in queue: removes first match). **No snackbar here** (the unused QueueScreen showed
  `l10n:removedFromQueue(title)`).

### 5.5 Lyrics view / overlay (`widgets/song_lyrics_overlay.dart`)
- Loads via `LyricsService.parseLyricsForSong(filePath)` (sidecar `.lrc`/`.txt`, see lyrics audit); reloads on
  song change. Loading spinner while parsing.
- None found: lyrics icon + `l10n:noLyricsFound` + `l10n:placeLrcFile`.
- **Unsynchronized**: centered plain lines, free scrolling.
- **Synchronized**: active line = last line with `time <= position` (from position stream). Fixed 64 dp rows,
  vertically padded so the active line can center; active line bold/larger/full color, others 40% alpha; max 2
  lines each. Auto-scrolls (300 ms ease) to the active line on change **unless the user scrolled within the last
  4 s**. **Tap a line → seek to its timestamp** and re-enable auto-scroll immediately.
- Same widget is used both full-screen (Lyrics tab) and in-art overlay.

### 5.6 Seek bar (`widgets/seek_bar.dart`)
Custom pill track (6 dp; grows to 10 dp + bigger thumb while interacting). Tap-down seeks immediately to the tapped
fraction. Horizontal drag updates a local value (UI shows dragged time), seeks on drag end. Labels: elapsed
"mm:ss" left, remaining "-mm:ss" right (tabular digits; minutes wrap at 60 — ⚠ hours not shown).

### 5.7 Related view
- Section `l10n:fromThisAlbum`: other songs with same album AND same artist.
- Section `l10n:moreByArtist(artist)`: same artist, different album.
- Rows are menu-less `SongTile`s; tap → switch back to player view and `playSong(song, allSongs)` (queue = entire
  library ⚠).
- ⚠ Enablement check uses (artist OR album) match, but lists require artist match → possible empty Related view
  when only the album name coincides.

---

## 6. Detail screens

### 6.1 Album detail (`screens/album_detail_screen.dart`)
- Blurred cover background (if setting on, 15% opacity). Collapsing SliverAppBar (expanded 340): back arrow,
  artwork 160 (Hero `album_art_<name>`), album name, artist, "`l10n:trackCount` • total duration".
- Action row: **`l10n:play`** (play first, queue = album songs), **`l10n:shuffle`** (quickShuffle album),
  **favorite album** heart (key `"<nameLower>|||<artistLower>"`, `toggleFavoriteAlbum`).
- Track list: `SongTile`s with full context menu, tap plays within album queue, long-press multi-select (§2.10).
- Song order = AlbumGroup order (from grouping model; presumably track number).
- No empty state (route returns "Album not found" earlier).

### 6.2 Artist detail (`screens/artist_detail_screen.dart`)
- Background: local artist image if exists else first existing song artwork (10% opacity, when blurred bg on).
- Header (expanded 280): large `ArtistAvatar`, name, "`albumCount` • `songCount` • duration".
- Actions: Play (first song, queue = artist songs), Shuffle, favorite artist heart (key `nameLower`,
  `toggleFavoriteArtist`).
- `l10n:albums` horizontal carousel (110 dp art, name, trackCount) → tap pushes AlbumDetail via Navigator
  (§1.5).
- `l10n:songs` section: all artist songs, full menu, multi-select.

### 6.3 Playlist detail (`screens/playlist_detail_screen.dart`)
- Reactive to provider playlists (re-reads by id on every notify). Songs = playlist `songIds` resolved against
  library (missing IDs silently dropped).
- Background: cover or first song art. Header (expanded 340): Hero `playlist_art_<id>` cover (custom → first song
  art → gradient icon), name, **description line** (playlist description or `l10n:yourOwnPlaylist`) — **tap
  opens Edit Description dialog**, "`songCount` • duration".
- **Overflow menu (⋮) items**: `l10n:exportToM3u`, `l10n:editDescription`, `l10n:changeCover`,
  `l10n:removeCover` (if cover), `l10n:renamePlaylist`, `l10n:deletePlaylist` (red). Same behaviors as the
  Playlists tab menu (§2.8), except delete: confirm dialog → **pops the screen first**, then deletes (no snackbar).
- Actions row (only if non-empty): Play / Shuffle with `playlistId` context (used for playlist play stats).
- Empty playlist: `SliverFillRemaining` with icon, `l10n:playlistIsEmpty`, `l10n:playlistIsEmptySubtitle`.
- Track list (`SliverReorderableList`):
  - Leading **drag handle** per row (hidden in select mode) → drag reorder → local reorder + `onReorderSongs`
    (optimistic persist of full ID list).
  - **Swipe left** (end-to-start, disabled in select mode) → remove from playlist → snackbar
    `Removed "<title>" from playlist.` (HARDCODED).
  - Context menu includes `l10n:removeFromPlaylist` → same remove + same HARDCODED snackbar.
  - Tap plays with `playlistId`. Long-press → multi-select, with extra "Remove from playlist" batch action.
- Tests: drag handles and `ReorderableDragStartListener` count == songs.

### 6.4 Favorites (`screens/favorites_screen.dart`)
- AppBar: back, centered title `l10n:favorites`, sort icon → sort sheet.
- Sections (each only if non-empty), all sorted with the same sort setting:
  1. `l10n:artists` + `l10n:artistCount(n)`: horizontal avatars (name, songCount) → `/artist`.
  2. `l10n:albums` + `l10n:albumCount(n)`: horizontal cards (art, name, artist) → `/album`. ⚠ This carousel
     **auto-scrolls** (1 px every 50 ms, loops to start) until the user scrolls it manually (then stops for the
     screen's lifetime).
  3. `l10n:songs` + "songCount • duration": song tiles (full menu, multi-select, tap plays favorites queue).
- Empty (none of the three): heart icon + `l10n:noFavoritesYet`.
- **Sort sheet**: title `l10n:sort`, subtitle `l10n:sortSubtitle`; radio `l10n:sortByName` (name),
  `l10n:sortByDuration` (duration; albums/artists by summed duration), `l10n:sortByDateFavorited` (date) —
  selecting a radio saves and **closes** the sheet; switch `l10n:sortAscending`. Defaults: date, descending.

### 6.5 Recently Played (`screens/recently_played_screen.dart`)
- AppBar: back, centered `l10n:recentlyPlayed`, shuffle action (`l10n:shufflePlay`; disabled when empty).
- Data: `StatsService.recentSongs(allSongs)` → entries {song, lastPlayedAt, listenCount}.
- Count label `l10n:songCount(n)`; then grouped sections by `formatRelativePlayDate`: `l10n:today`,
  `l10n:yesterday`, `l10n:thisWeek` (<7 days), else localized "Month Year".
- Rows: SongTile with metadata `l10n:listensCount(n)`, full menu, multi-select; tap plays with whole recent list as
  queue.
- Empty: history icon, `l10n:noRecentlyPlayedSongs`, `l10n:noRecentlyPlayedSongsSubtitle`, button
  `l10n:allSongs` → closes route (back to Home).

### 6.6 Changelog (`screens/changelog_screen.dart`)
AppBar `l10n:changelogTitle` + refresh (force refetch). Loading spinner; error: icon + `l10n:failedToLoadChangelog`
+ `l10n:tryAgain`; success: Markdown rendering of `UpdateService.fetchChangelog()` (fallback "No changelog
available." HARDCODED).

### 6.7 Queue screen (unrouted, see §1.1)
Same as Up Next but as a full screen: title `queueNOfM`, actions scroll-to-current + save-as-playlist, swipe remove
shows `l10n:removedFromQueue(title)` snackbar, empty state `l10n:queue` title + `l10n:queueEmpty`.

---

## 7. Stats screen (`screens/stats_screen.dart`)

- Entry: Settings → Stats section → `l10n:viewStatistics` (subtitle `l10n:viewStatisticsSubtitle`).
- AppBar: back, title `l10n:listeningStatistics`, trash action (tooltip `l10n:resetStatistics`) → confirm
  dialog `l10n:resetStatisticsTitle` / `l10n:resetStatisticsWarning`, confirm label `l10n:reset` →
  `StatsService.reset()`.
- Horizontal **PageView with 5 pages** (swipeable) + bottom nav: prev chevron (disabled on first), 5 dot
  indicators (active elongated), next chevron (disabled on last).

**Page 1 — Overview**
- Hero card: `completeSongListens` number + `l10n:completeListens` (newline replaced with space).
- 2×3 metric grid: `l10n:albums` (albums listened count), `l10n:artists` (artists listened), `l10n:playlists`
  (playlist listen count), `l10n:shuffleSessions` (shuffle session starts), `l10n:skips` (total skips),
  `l10n:restarts` (total restarts).
- Card `l10n:recentlyPlayed` / `l10n:recentlyPlayedActivitySubtitle` → `/recently-played`.
- `l10n:funFacts` list: `l10n:firstSongPlayed` (title; only if any), `l10n:mostPlayedSong` ("title (Nx)"; only if
  any), `l10n:uniqueSongsPlayed` (`l10n:songsCountShort(n)`), `l10n:mostActiveDay` (string from service).

**Page 2 — Top Songs** (`l10n:mostPlayedSongs`, top 5; empty `l10n:noSongStatsYet`)
- Card per song: rank "#1".."#5" (gold/silver/bronze/outline colors), menu-less SongTile (tap → play song with
  **whole library** as queue), expand chevron → reveals per-song stats: plays (label `l10n:play`), skips
  (`l10n:skips`), restarts (`l10n:restarts`).

**Page 3 — Top Albums** (`l10n:mostPlayedAlbums`, top 5; empty `l10n:noAlbumStatsYet`)
- Row: artwork, album, artist, "Nx" pill, chevron → `/album`.

**Page 4 — Top Artists** (`l10n:mostPlayedArtists`; empty `l10n:noArtistStatsYet`)
- Row: rank, avatar, name, `l10n:playsCount(n)`, open-in-new icon; row or icon → `/artist`.

**Page 5 — Top Playlists** (`l10n:mostPlayedPlaylists`; empty `l10n:noPlaylistStatsYet`)
- Row: rank, cover (custom → first song art → circle icon), name, "`songCount` · `playsCount`" (song count here
  = raw `songIds.length`, ⚠ inconsistent with other screens that count only existing songs), open icon → `/playlist`.

Refreshes on any PlayerProvider notification.

---

## 8. Song info bottom sheet (`widgets/song_info_bottom_sheet.dart`)

Root-navigator modal, max 60% height, drag handle, title `l10n:songInformation`, scrollable grouped cards.

**Group `l10n:metadataGroup`** (each value row is tappable → copy value to clipboard, light haptic, snackbar
"`<label>: l10n:copiedToClipboard`", 2 s, replaces current snackbar; small copy icon hint):
- `l10n:titleLabel` (displayTitle), `l10n:artistLabel`, `l10n:albumLabel`, `l10n:trackLabel` (if any),
  `l10n:genreLabel` (if any), `l10n:yearLabel` (if any), `l10n:durationLabel`.

**Group `l10n:fileInfoGroup`**:
- `l10n:filePathLabel`: monospace **SelectableText** (not tap-to-copy) + action buttons:
  - `l10n:openFolder` (Android only) → `MusicScanner.openFileFolder(path)`; failure snackbar `l10n:openFolderError`.
  - `l10n:copyPath` → copy path, snackbar "`filePathLabel: copiedToClipboard`".
  - `l10n:shareFile` (Android only) → system share of the file.
- `l10n:fileSize` (B/KB/MB/GB formatting) if known.
- `l10n:dateModified` (from `lastModifiedMs`, locale `yMMMd + jm`).
- `l10n:dateCreated` — from file `stat().changed`, shown only when it differs from modified.
- `l10n:formatLabel` (uppercase extension) if known.

**Group `l10n:audioPropertiesGroup`** (shown if bitrate/samplerate/replayGain present):
`l10n:bitrateLabel` ("N kbps"), `l10n:sampleRateLabel` ("44.1 kHz"), `l10n:replayGainTrackGainLabel`,
`l10n:replayGainAlbumGainLabel` ("+x.xx dB"). ⚠ Group visibility checks `song.replayGain` while rows use
`replayGainTrack`/`replayGainAlbum` — can render an empty group.

Opened from: song tile menu `l10n:songInfo`, Now Playing ⋮ menu. Test asserts title/artist/album shown, one
SelectableText, "Copy Path", tap on value → SnackBar.

---

## 9. Dialogs & sheets catalogue

### 9.1 Playlist selector (`widgets/playlist_selector.dart`)
Root-navigator sheet, max 60% height, keyboard-aware. Calls `loadPlaylists()` on open.
- Header `l10n:addToPlaylist` + "+" button (tooltip `l10n:createPlaylist`) → inline create dialog
  (`l10n:createPlaylist`, hint `l10n:playlistName`, `l10n:cancel` / `l10n:create`): creates playlist, then adds the
  target song(s) to it (looked up by name, last match). Multi mode: closes sheet + snackbar
  `l10n:batchAddedToPlaylist(n, name)`. Single mode: dialog closes, sheet stays (new row shows check).
- Empty: icon + "No playlists created yet" (HARDCODED).
- Rows: cover/first-art/icon, name, `songCount` (live existing songs only).
  - **Single-song mode**: rows already containing the song are tinted with a check icon; tapping **toggles**:
    remove → snackbar `Removed "<title>" from <playlist>.` / add → `Added "<title>" to <playlist>.` (both
    HARDCODED). Sheet stays open.
  - **Multi-song mode**: tap → `addSongsToPlaylist` (dedupe is provider's job), close sheet, snackbar
    `l10n:batchAddedToPlaylist(n, name)`.

### 9.2 Rename playlist dialog
Title `l10n:renamePlaylist`, text prefilled with current name, hint `l10n:playlistName`, `l10n:cancel` /
`l10n:rename`. Empty → no-op; unchanged → just close; else close + `onRename`.

### 9.3 Edit description dialog
Title `l10n:editDescription`, 3-line outlined field labelled `l10n:description`, prefilled; `l10n:cancel` /
`l10n:save`. Empty text → description set to null (header falls back to `l10n:yourOwnPlaylist`).

### 9.4 Create playlist dialog / Save queue dialog
- Create (`CreatePlaylistDialogContent`): `l10n:createPlaylist`, hint `l10n:playlistName`, `cancel` / `create`;
  ignores empty names; no snackbar.
- Save queue (`SaveQueueDialogContent`): title `l10n:saveQueueAsPlaylist`, `cancel` / `save` →
  `saveQueueAsPlaylist(name)` → snackbar `l10n:savedPlaylist(name)`.

### 9.5 Sleep timer sheet (`widgets/sleep_timer_sheet.dart`)
Title `l10n:sleepTimer` (bedtime icon).
- **Inactive**: big "`l10n:sleepTimerMinutes(n)`" value, hint `l10n:sleepTimerDragHint`; slider 1–60 min (1-min
  steps), initial = `settings.sleepTimerDefaultMinutes` (default 5); preset chips 5/10/15/20/30/45/60 (default
  preset outlined/bold); switch `l10n:finishCurrentSong` / `l10n:finishCurrentSongSubtitle` (initial from
  settings `sleepTimerFinishSong`); button "`l10n:startTimer` (`sleepTimerMinutes`)" → `startSleepTimer(duration,
  finishSong)` and close.
- **Active**: card with `l10n:sleepTimerMusicStopsIn` + live countdown (h:mm:ss or m:ss) — or, when finishing the
  current song, `l10n:finishCurrentSong` + `l10n:sleepTimerFinishingSong`; buttons `l10n:cancelTimer` (stop +
  close) and `l10n:plusOneMin` (extend by 1 min, stays open).

### 9.6 MFX (Music Effects) sheet (`widgets/mfx_bottom_sheet.dart`)
Max 60% height. Header `l10n:musicEffects` + `l10n:experimental` badge.
- Duration info chip: `l10n:durationLabel`: original duration; if speed ≠ 1 → "m:ss (orig.)" (HARDCODED
  "(orig.)") → effective duration "(1.3x)".
- `l10n:presetSpeedAndPitch` + `l10n:resetAll` (only when a preset is active). Horizontal `PresetCard` carousel
  (auto-centered on selected): presets in order superSlowed, slowed075 (`mfxPercentSlower('25')`), slowed080
  ('20'), slowed (0.85, `mfxSlowed`), slowed090 ('10'), slowed095 ('5'), normal (`mfxNormal`), spedUp105..120
  (`mfxPercentFaster`), spedUp (`mfxSpedUp`), nightcore (1.30, `mfxNightcore`), superSpedUp. Pitch = speed
  (tape-style). Each card title + subtitle (`getSubtitle`) + icon.
- Switches: `l10n:mfxWarmth` (+Subtitle, reverb), `l10n:mfxLoFi`, `l10n:mfxBassBoosted`, `l10n:mfxBluetoothPatch`.
- `l10n:customSpeed`: `SpeedSlider` with stops 0.5/1.0/1.25/1.5/2.0, reset icon (`l10n:resetSpeed`), value label;
  disabled (50% alpha) while a preset is active.
- Android only: `CustomEqualizerWidget` — `l10n:customEqualizer` switch (off → `resetAllMfx()` ⚠ resets
  everything, not just EQ), preset chips `l10n:eqFlat/eqPop/eqRock/eqJazz/eqClassical` (5-band fractions mapped
  onto device bands, scaled to min/max dB), vertical sliders per band with Hz/kHz labels (commit on drag end).
- Footer `l10n:mfxResetAll` (error color) → `resetAllMfx()`.

### 9.7 Confirm delete dialog
Warning icon + title, message, `cancel` (returns false) / red confirm (default `l10n:delete`, custom label
supported) returning true. Used for playlist delete and stats reset.

### 9.8 Update dialog (`widgets/update_dialog.dart`, Android)
Title `l10n:updateAvailable`, `l10n:updateAvailableMessage(version)`; ABI choice chips (HARDCODED header
"Architecture / APK Package", "Recommended" badge on recommended ABI); `l10n:changelogLabel` + scrollable
changelog. Actions: `l10n:later`, open-in-browser icon (HARDCODED tooltip), **"Update Now"** (HARDCODED) →
downloads APK with progress ("Preparing download...", "Downloading update... N%", "Opening installer..." —
HARDCODED) → installs; failure falls back to browser / error "Download failed. Tap to try via browser."
During download only `l10n:cancel`.

---

## 10. Onboarding (`screens/onboarding_screen.dart`)

Non-swipeable 3-page PageView, "SONORA" wordmark top-left (HARDCODED), dot indicator (3), FAB bottom-right.

1. **Welcome**: illustration, `l10n:welcomeToSonora`, name field `l10n:yourNameOptional` (prefilled **"User"**),
   `l10n:onboardingDescription`. FAB → next.
2. **Permissions**: card with two status rows (check / empty circle): `l10n:accessAudioAndImageFiles` +
   `l10n:accessAudioSubtitle` (Android 13+: audio AND photos; older: storage), `l10n:showNotifications` +
   `l10n:showNotificationsSubtitle`. Title `l10n:permissionsNeeded`, `l10n:permissionsExplanation`. Button
   `l10n:configurePermissions` → `requestAllPermissions()`, re-check, **auto-advance** if granted; when all
   granted button shows `l10n:allPermissionsGranted` (disabled). FAB **disabled until storage AND notifications
   granted**. ⚠ On iOS the status check is skipped (flags stay false) so the FAB stays disabled — the only way
   forward is the button's auto-advance.
3. **Music folder**: icon, `l10n:setupMusicDirectory`, text iOS `l10n:addMusicHint`, else `l10n:chooseMusicFolder`
   / `l10n:selectedDirectory` + monospace path. Button iOS `l10n:addMusic`, else `l10n:selectFolder` /
   `l10n:changeFolder` → directory picker (iOS: file import into sandbox; iOS preselects the app music folder on
   init). Picker error → snackbar `l10n:failedToSelectDirectory`.
   FAB extended `l10n:getStarted` (enabled only when folder selected) → `onComplete(folder, name or "User")`.

**On finish** (app.dart): save userName, enable greeting title, mark onboarding complete, route to loading, save
folder, import + scan, update provider/playlists, refresh sync stats, go Home, snackbar (HARDCODED, §0.3).

---

## 11. Smaller widgets (behavioral notes)

- `AlbumArt`: file-existence cache, thumbnail store lookup, sized decode; placeholder = primary→tertiary gradient +
  music note icon.
- `ArtistAvatar`: local artist image → first existing song art → person icon.
- `BlurredCoverBackground`: renders nothing unless `blurredBackgrounds` setting on; blur faked by 32 px decode.
- `MarqueeText`: only scrolls when text overflows; resets on text change.
- `AnimatedVinyl`, `AudioVisualizer`: see §5.
- `DevBadge`: "DEV" pill for dev builds.
- `ThemeColorSelector`, `PresetCard`: settings/MFX visual pickers (ThemeColorSelector belongs to Appearance settings).
- `CustomScrollbar`: §2.4.

---

## 12. Tests in scope (what they lock in)

| Test file | Coverage |
|---|---|
| `widget_test.dart` | Not UI: `Song.durationFormatted`, `displayTitle` cleanup (feat./redundant artist), MFX effective duration math. |
| `batch_actions_test.dart` | Scanner batch add/remove to playlist & bulk favorite; SongTile selection overlay (check / unchecked icons, `onSelect`); MultiSelectActionBar: "1 selected", select-all & close callbacks, 5 action icons. |
| `dialogs_and_sheets_test.dart` | ConfirmDelete true/false; Rename prefill & submit; EditDescription prefill & submit; AnimatedFavoriteButton icons/toggle; SpeedSlider; PlaylistsTab uses first song art as cover + "2 songs"; PlaylistDetail drag handles; SongInfo sheet content/SelectableText/"Copy Path"/tap→SnackBar; QueueScreen scroll-to-current; repeat cycle off→all→one→off. |
| `sync_prompt_banner_test.dart` | Banner presence by flag; "Remind Next Month" → postpone & hide; "Sync Now" → resync, hide, progress bar during sync. |
| `large_playlist_simulation_test.dart` | 500/1000/2000-song lookups & shuffle generation; playlist cover crop/downscale; UpdateService rate limiting; CustomScrollbar renders & scrolls. |

---

## 13. Porting risks & recommendations

1. **Hardcoded English strings** (must add to resources): sync banner "Remind Next Month"; all gate screens;
   app-level snackbars (folder configured / postponed / reset); `syncedXSongs` duration fragment " in Xms";
   playlist selector add/remove snackbars & empty text; playlist-detail "Removed … from playlist."; share text
   "Sharing N songs"; MFX "(orig.)"; Update dialog strings; route error texts; "SONORA", "DEV",
   "No changelog available.".
2. **Mini player swipe-down = stop & clear queue** without undo — keep or add confirmation/undo deliberately.
   Swipe direction semantics (left=next, right=previous) are inverted in code naming.
3. **Now Playing hosting**: modal sheet on shell navigator with nested modal Album/Artist sheets; artist→album uses
   imperative push. Pick a single navigation model in Compose (e.g. NP as full-screen destination or
   `ModalBottomSheet`, detail screens as nav destinations that collapse NP first).
4. **System back inside NP sub-views** closes the sheet instead of returning to player view — decide intended UX.
5. **Shared query across Home tabs**; selection only on Songs tab and cleared on tab change.
6. **Sync prompt computed once at startup**, only on Songs tab, requires an existing `last_sync_timestamp`;
   snooze = 30 days (`postpone_sync_until`). Preserve pref keys if migrating data.
7. **Dead/legacy code**: `QueueScreen` unrouted; `SongActivityView.recentlyPlayed` empty-state branch unreachable;
   `openStats` unused; Hero tags without sources.
8. **Bugs to not replicate (or to fix knowingly)**: SongInfo audio group visibility uses `replayGain` vs row fields;
   Related tab enablement vs content mismatch; Up Next swipe removes first match of song id (duplicates);
   Top Playlists song count includes missing songs; custom EQ switch-off resets all MFX; seek bar hides hours;
   Related & Stats top-song playback use the whole library as queue.
9. **Fake visualizer** (sine animation) — a native port could use `Visualizer` API but that needs RECORD_AUDIO;
   simplest parity is keeping it decorative.
10. **Favorites albums auto-scroll carousel** (timer-driven) — easy to forget; stops permanently after user
    interaction.
11. **Lyrics sync constants**: 64 dp line height, 4 s user-scroll grace, tap-to-seek; overlay vs full tab share
    one component.
12. **Immersive mode** is a persisted setting toggled by tapping artwork (not just transient UI state).
13. **Fast scroller with section bubble** per tab/sort (letters, minutes, play counts) — Compose has no built-in;
    needs custom implementation.
14. **Onboarding iOS permission path** (FAB disabled, auto-advance only) — Android-only port avoids it, but keep
    the "require storage + notification before continuing" rule; onboarding forces greeting title on and default
    name "User".
15. **Optimistic reorder** of playlists and single-sheet duplicate guard are behavioral details to preserve.
