# Sonora — DATA & LIBRARY layer audit (port spec for native Kotlin/Android)

Source repo: `/Users/yurtemre/Code/antigravity/kind-salk` (Flutter, read-only audit).
Scope: `lib/models/*`, `lib/services/music_scanner.dart` + `scanner_*.dart`, `library_search_index.dart`,
`backup_service.dart`, `stats_service.dart`, `thumbnail_store.dart`, `image_preflight_service.dart`,
`lyrics_service.dart`, `replay_gain_reader.dart`, `permission_service.dart`,
`lib/providers/player_library.dart`, `lib/providers/player_persistence.dart`, `lib/utils/*`,
and the native half of scanning in `android/app/src/main/kotlin/de/yurtemre/sonora/MainActivity.kt`
(the Android scan is already Kotlin — reuse it).

Conventions in this doc: "lower" = Dart `toLowerCase()` (Unicode-aware, locale-independent → use
`lowercase(Locale.ROOT)` in Kotlin). Dart `String.compareTo` compares UTF-16 code units (NOT
locale collation) → use plain Kotlin `String.compareTo`, never `Collator`. All maps whose
"first"/"order" matters are insertion-ordered (`LinkedHashMap` in Kotlin).

---------------------------------------------------------------------------------------------------

## 1. Data models

### 1.1 `Song` (`lib/models/song.dart`)

Plain immutable class. **No `==`/`hashCode` override → identity equality.** Code compares songs by
`id` or `filePath` everywhere it matters.

| Field | Dart type | Default | Library-cache JSON key | Notes |
|---|---|---|---|---|
| id | int | required | `id` | Android: MediaStore `_ID`. Legacy/dir scan: incrementing counter (see §2.6). |
| title | String | required | `title` | |
| artist | String | required | `artist` | Raw tag string, may contain multiple artists ("A feat. B"). |
| album | String | required | `album` | |
| duration | Duration | required | `duration_ms` (int ms) | |
| filePath | String | required | `file_path` | Absolute filesystem path (also the identity for incremental sync). |
| artworkPath | String? | null | `artwork_path` | Absolute path to a cached/extracted image file. |
| format | String? | null | `format` | MediaStore path: lowercase file extension (`mp3`). Dir scan: lofty format string (native lib's name). |
| bitrate | int? | null | `bitrate` | Only filled by dir scan (lofty). Never from MediaStore. |
| samplerate | int? | null | `samplerate` | Same. |
| isFavorite | bool | false | `is_favorite` | |
| favoriteDateMs | int? | null | `favorite_date_ms` | Epoch ms. On decode: if missing and `is_favorite==true` → **DateTime.now()** (non-deterministic migration). |
| lastModifiedMs | int? | null | `last_modified_ms` | File mtime in epoch ms (MediaStore DATE_MODIFIED*1000). Used for incremental sync and "recent" sort. |
| fileSize | int? | null | `file_size` | Bytes. |
| hasLyrics | bool | false | `has_lyrics` | Decoded as `== true` (non-bool → false). |
| dominantColor | int? | null | `dominant_color` | ARGB32 int (`0xFFRRGGBB` as signed/unsigned? Dart int — positive e.g. 4294901760). Store as Long or keep int bits. |
| trackNumber | int? | null | `track_number` | MediaStore TRACK raw (see §2.3: may be `disc*1000+track`, and **0 instead of null**). |
| discNumber | int? | null | `disc_number` | Only dir scan fills it. |
| genre | String? | null | `genre` | Only dir scan fills it. |
| year | int? | null | `year` | MediaStore YEAR (0 when unknown, not null). |
| replayGainTrack | double? | null | `replaygain_track_gain` | dB. Decoded via `(num?)?.toDouble()`. |
| replayGainAlbum | double? | null | `replaygain_album_gain` | dB. |
| replayGainTrackPeak | double? | null | `replaygain_track_peak` | Linear. |
| replayGainAlbumPeak | double? | null | `replaygain_album_peak` | Linear. |
| replayGainScanned | bool | false | `replaygain_scanned` | Decoded `== true`. Marks "tags already read". |

Encoding: every key is always written, nulls as JSON `null`. Decoding uses hard casts for
`id`,`title`,`artist`,`album`,`duration_ms`,`file_path` (required; a bad entry throws → whole cache read
fails → treated as empty by non-strict reader).

Derived (computed once, lazily):
- `titleLower`, `artistLower`, `albumLower` = lowercase of the raw fields.
- `durationFormatted` = `"${inMinutes}:${(inSeconds%60) padded 2}"` → `"3:05"`, `"65:00"` (minutes NOT
  wrapped into hours).
- `replayGain` → `ReplayGainInfo` or null if neither track nor album gain.
- `replayGainExtras` → map (`rg_track_gain`, `rg_album_gain`, `rg_track_peak`, `rg_album_peak`; null
  entries omitted) or null when no gain.
- `displayTitle` (reads global settings `filter_title_features`, `filter_title_artist`), see §10.1.

`copyWith`: null-coalescing (cannot set a field back to null). `withReplayGain(info?)` builds a copy
with exactly the 4 RG values from `info` (all null when info null) and `replayGainScanned=true`.

### 1.2 `Playlist` (`lib/models/playlist.dart`)

| Field | Type | JSON key |
|---|---|---|
| id | String | `id` (creation: `DateTime.now().millisecondsSinceEpoch.toString()`) |
| name | String | `name` |
| songIds | List<int> (mutable!) | `song_ids` |
| coverImagePath | String? | `cover_image_path` |
| description | String? | `description` |

Derived `nameLower`. `copyWith(clearCoverImage, clearDescription)` flags allow nulling. Identity equality.
Duplicates in `songIds` are prevented only by `addSongsToPlaylist` (skips ids already present);
`createPlaylistFromSongs` (save queue) and reorder can contain duplicates.

### 1.3 `AlbumGroup`, `ArtistGroup` (`lib/models/grouping.dart`)

- `AlbumGroup { name, artist, songs }`; derived `nameLower`, `artistLower`,
  `latestModifiedMs` = max of `song.lastModifiedMs ?? 0` (0 if none).
- `ArtistGroup { name, songs, albums, localImagePath? }`; derived `nameLower`.
- Identity equality (topAlbums uses `AlbumGroup` as a map key by identity).
- Favorite-album key everywhere: `"${album.nameLower}|||${album.artistLower}"`. Favorite-artist key:
  `artist.nameLower`.

### 1.4 `ReplayGainMode` / `ReplayGainInfo` / `ReplayGainExtras` (`lib/models/replay_gain.dart`)

- enum `off | track | album`; stored by `.name` in pref `replay_gain_mode`; unknown/null → `off`.
- `ReplayGainInfo { trackGain?, albumGain?, trackPeak?, albumPeak? }`, `hasGain = trackGain!=null || albumGain!=null`.
- Extras keys: `rg_track_gain`, `rg_album_gain`, `rg_track_peak`, `rg_album_peak`. `parse` reads `num` →
  double; returns null if no gain.
- Math: see §9.

### 1.5 `SongActivityView` (`lib/models/song_activity.dart`)

- enum `all | recentlyPlayed | mostPlayed | leastPlayed`; pref `song_activity_view` by `.name`, default `all`.
- `qualifyingRecentPlayThreshold(d)`: `clamp(round(d_ms*0.25), 10_000, 30_000)` then `clamp(0, d_ms)`.
  Examples: 5 s → 5 s; 20 s → 10 s; 5 min → 30 s.
- `formatRelativePlayDate(playedAt, now)`: day diff of local *dates* (rounded ms/86400000):
  `<=0` → "Today", `1` → "Yesterday", `<7` → "This week", else localized "Month Year".

### 1.6 `MfxPreset` (`lib/models/mfx_preset.dart`) — data part only

Enum (name, speed): superSlowed 0.70, slowed075 0.75, slowed080 0.80, slowed 0.85, slowed090 0.90,
slowed095 0.95, normal 1.00, spedUp105 1.05, spedUp110 1.10, spedUp115 1.15, spedUp120 1.20,
spedUp 1.25, nightcore 1.30, superSpedUp 1.50. `pitch == speed`. `fromSpeed` tolerance `< 0.001`;
`fromName` by enum name. Stored as pref `mfx_preset_name` (preferred) or legacy `mfx_preset` (double).

---------------------------------------------------------------------------------------------------

## 2. Scanning

### 2.1 How the folder is chosen

- Android: `FilePicker.getDirectoryPath()` (SAF tree picker; the plugin converts the tree URI to a
  **filesystem path** like `/storage/emulated/0/Music`). Stored as plain path in pref
  `scan_folder_path`. Everything downstream works on `java.io.File` paths, **not** SAF URIs.
- iOS: fixed `Documents/Music` (auto-created); files are copied in via a file picker
  (`ScannerImport`, extensions `mp3, m4a, aac, flac, wav`; existing target names are skipped). Ignore
  for Android port.
- `null` folder = "no folder configured".
- Permissions (`PermissionService`): Android SDK ≥ 33 → request `READ_MEDIA_AUDIO` (granted ⇒ OK) and
  best-effort `READ_MEDIA_IMAGES`; SDK < 33 → `READ_EXTERNAL_STORAGE`. Then best-effort
  `POST_NOTIFICATIONS` if not granted. Return value = audio/storage granted. SDK int obtained via method
  channel `de.yurtemre.sonora/volume` `getAndroidSdk` (0 on failure → legacy branch). Exception → fallback
  to storage permission.

### 2.2 Entry points & orchestration

- `scanAllSongs()` (app start): read library cache file only (no disk scan), load artist images, sort
  by "songs" tab settings (`sort_by_songs`/`sort_ascending_songs`, default `title`/asc — see §5.3 bug),
  kick off image preflight. Returns cached list.
- `importFromFolder(path)`: `setScanFolder(path)` then `syncLibrary()`.
- `syncLibrary(maxWorkers=4)` = `syncLibraryFast` + image preflight.
- `syncLibraryFast`:
  1. folder = pref; cache = read file.
  2. If folder set and `!Directory(folder).exists()` → **return `[]` and DO NOT touch the cache**
     (unmounted SD card protection; favorites live in the cache).
  3. Android: `_scanViaMediaStore(folder ?: '', cache, isFast=true)`. If it returns a non-empty list:
     fill ReplayGain (§2.8), load artist images, sort by songs-tab settings, write cache
     (not awaited), write `last_sync_time` (formatted string), `last_sync_timestamp` (ms),
     `metadata_version=1`, `last_perf_log` (debug text), `last_sync_duration_last` (ms). Return.
     If MediaStore returned `[]` (e.g. not indexed yet) → fall through with `skipMediaStore=true`.
  4. If folder `null`: keep only cached songs whose file exists, fill RG, write cache, return.
  5. Else `syncLibraryLegacy(skipMediaStore)` (directory walk, §2.4).
  6. Any exception → return cached list.
- Sync triggers in app: onboarding/folder pick, manual "sync now", and a banner prompt when
  `last_sync_timestamp` older than 30 days and `now >= postpone_sync_until` (postpone = now+30 days).
  `postpone_sync_until` is cleared by the legacy sync (not by the fast MediaStore path).
- `deleteSong(song)`: removes it from the cache by `filePath` (file on disk untouched). It reappears
  on next sync.

### 2.3 Android MediaStore scan (native, `MainActivity.queryMediaStore`) — already Kotlin

Channel `de.yurtemre.sonora/mediastore`, method `scanMediaStore {folderPath, isFast}` run on a
background `Thread`.

Query: `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`, projection `_ID, TITLE, ARTIST, ALBUM, DURATION,
DATA, ALBUM_ID, TRACK, YEAR, DATE_MODIFIED, SIZE` (+`COMPOSER` on R+, unused). Selection:
`IS_MUSIC != 0` AND (`DATA LIKE ? ESCAPE '\'` OR …) for each folder variant; args
`"<escaped variant>/%"` (escape `\`,`%`,`_`). Variants: normalized folder (trailing `/`/`\` trimmed,
`\`→`/`), plus `/sdcard…` ⇄ `/storage/emulated/0…` alias. Each row is additionally re-checked
case-insensitively with `startsWith("$variant/")`. Empty folder → whole device (`IS_MUSIC != 0`).
No sort order.

Per row → map: `id` (=_ID, **becomes Song.id**), `title` (empty → `File.nameWithoutExtension`),
`artist` (empty or `"<unknown>"` → `"Unknown Artist"`), `album` (empty/`"<unknown>"` →
`"Unknown Album"`), `duration_ms`, `file_path`, `artwork_path`, `track_number` (`getInt` → 0 when
absent; Android encodes disc in thousands: disc 2 track 3 = 2003 — **not decoded by app**), `year`
(0 when absent), `last_modified_ms = DATE_MODIFIED*1000`, `file_size`, `album_id`, `has_lyrics`
(sidecar `<path-without-ext>.lrc` or `.txt` exists; only when the last `.` index > 0).

Album art cache (per album, not per song): dir `context.cacheDir/album_art/`,
file `art_album_<albumId>.jpg`, "no art" marker `art_album_<albumId>.none`, temp `<name>.tmp`.
- If jpg exists & size>0 → used. Else artwork null and albumId>0 without `.none` marker → "missing".
- `extractAlbumArt`: API ≥ 29 → `contentResolver.loadThumbnail(albums/<id>, Size(720,720))`
  (`ALBUM_ART_MAX_PX = 720`) → JPEG quality 90; API < 29 → copy stream from
  `content://media/external/audio/albumart/<id>`. Write temp then rename. `FileNotFoundException` or
  empty → create `.none` marker (never retried). Other exception → no marker (retry next scan).
- First scan (all art null and some missing): extract synchronously with a fixed pool of
  `availableProcessors().coerceIn(1,4)` threads, await ≤ 5 min, patch rows; also resolve artist images
  synchronously.
- Later scans with `isFast` and missing ids: background thread extracts art (rows NOT patched — art
  appears on the *next* sync) and resolves artist images, merging into
  `getExternalFilesDir(null)/artist_images.json` (see §2.7 bug).
- Non-fast mode resolves artist images inline per row.
- Prune: after a successful non-empty query, background-delete files matching
  `^art_album_(\d+)\.(jpg|none|jpg\.tmp)$` whose id is not in the current result.

Returns `{songs, artist_images, perf_query_cursor_ms, perf_cursor_loop_ms, perf_total_kotlin_ms}`.

Dart post-processing (`_scanViaMediaStore`): builds maps from cache by `filePath`
(song, isFavorite, favoriteDateMs, dominantColor, id). For each row:
- `has_lyrics` from native (fallback: check sidecars in Dart).
- **Unchanged rule**: cached exists AND `cached.lastModifiedMs == mtime` AND `cached.fileSize == size`
  AND (`artPath == null` OR `cached.artworkPath == artPath`) → keep cached object (only `hasLyrics`
  updated if changed). Note: a cached artwork is kept even if MediaStore now reports none.
- Else new `Song`: id = native `_ID` (fallbacks: cached id by path, then `max(cachedIds)+1`
  counter — effectively never used); title/artist/album with trim-empty fallbacks
  (title → `filePath.split(pathSeparator).last` **with extension**, here differs from native which
  already strips it); `format = lowercase extension`; carries over isFavorite/favoriteDateMs/
  dominantColor from cache by path; `trackNumber`, `year`, `hasLyrics`; **discNumber, genre, bitrate,
  samplerate are left null**; `replayGainScanned=false` (re-read RG).
- Songs that disappeared from MediaStore are dropped (and with them their favorite flag).
- If native returned `artist_images` non-empty: replaces in-memory map and writes
  `<appDocs>/artist_images.json` (NOT the prefs copy).

### 2.4 Directory scan (`syncLibraryLegacy`, used when not Android or MediaStore empty/failed)

Runs in an `Isolate`. If `metadata_version < 1` the cache is ignored for diffing (full re-parse,
but favorites/ids are then lost — they come from `verifiedSongs` which is derived from the ignored cache).

1. Recursive `listSync(followLinks=false)` of the scan folder. Per file (name lowercased):
   - `cover.jpg|cover.png|cover.webp` → `localCoverImageDirs[parentDir] = path` (last wins).
   - other `.jpg|.png|.webp|.jpeg` → `localImageFiles[parentDir] += path` (also under `\`→`/` key).
   - else extension (after last `.`) in **audio set**:
     `mp3, m4a, mp4, aac, flac, ogg, opus, wav, wma, amr, 3gp, ts, mkv, mid, midi` → audio file.
   Errors abort listing silently (partial list).
2. `verifiedSongs` = cached songs whose path is in found set. Unchanged rule: `stat.mtime ms ==
   cached.lastModifiedMs && size == cached.fileSize && cached.artist != 'Local Audio' &&
   cached.album != 'Synced Folder' && cached.format != null` → keep (hasLyrics refreshed). The two
   sentinel strings are legacy placeholder values from an old version → force re-parse.
3. New/changed files: ids pre-allocated: existing id by path, else counter starting at
   `max(verified ids)+1` (or 1). isFavorite carried by path. **favoriteDateMs and dominantColor are NOT
   carried over** for re-parsed files (bug vs MediaStore path).
4. Tag read: `audio_tags_lofty.readMetadata(path, true)` (Rust lofty via FFI, with picture). Fields:
   `title`, `artist ?? albumArtist` (trimmed; note `??` only applies when artist is null, not empty),
   `album`, `format`, `bitrate`, `samplerate`, `track`, `disc`, `genre`, `year`, `duration`.
   Kotlin replacement: any tag lib (e.g. jaudiotagger / TagLib / `MediaMetadataRetriever`).
5. Embedded picture → written raw (no resize, whatever format, always `.jpg` name) to
   `<appDocs>/artwork_<nowMs>_<songId>.jpg`. Old files are never deleted (leak on every re-parse).
6. Art fallback: `cover.*` in the file's dir, else in its parent dir.
7. Fallbacks:
   - title: tag title, else filename without extension.
   - artist: if null/empty/"Unknown Artist" → grandparent folder name, only if grandparent path
     `startsWith(scanFolder)` and name non-empty and not `music` / `Download` (case-sensitive) →
     else "Unknown Artist". Test: `<root>/Flat Song.mp3` → Unknown Artist;
     `<root>/Some Artist/Album/Nested Song.mp3` → "Some Artist".
   - album: if null/empty/"Unknown Album" → parent folder name (if non-empty) else "Unknown Album".
     Note: a file directly in the scan root gets the root folder name as album.
   - trackNumber: if no tag, regex `^(\d+)\s*[-_.]?\s*` on file name.
   - duration: lofty or 0.
8. `hasLyrics`: `<pathWithoutExt>.lrc` or `.txt` exists (needs a `.` in path).
9. Artist image detection for every kept song (§2.7 variant A) → map saved to
   `<appDocs>/artist_images.json` (not prefs).
10. Fill ReplayGain, sort by songs-tab settings, write cache, set `last_sync_time`,
    `last_sync_timestamp`, remove `postpone_sync_until`, `metadata_version=1`,
    `last_sync_duration_last`.
11. On exception: prints and returns `[]` (UI then shows empty library until next sync!).

Unreachable/missing folder → `[]`, cache untouched. `legacySyncLibrary()` is a dead benchmark copy
(note: it *does* wipe the cache when folder missing) — don't port.

### 2.5 Sync timestamp string

`"$Mon $day, $year at $h:$mm:$ss $AM/PM"` with English month abbreviations
(`Jan…Dec`), 12h clock (`0→12`, `13→1`), minutes/seconds zero-padded, day/hour not padded.
Example: `Mar 7, 2026 at 3:04:05 PM`. Stored in pref `last_sync_time` (not localized).

### 2.6 Song id stability (critical)

Playlists, favorites-by-id, stats, last-played queue all reference `Song.id`.
- Android MediaStore path: id = MediaStore `_ID` (stable while the MediaStore row lives; changes if the
  file is re-indexed/moved).
- Directory path: incremental integers. Switching between the two paths re-ids songs that are
  re-parsed (existing-path ids are reused, so it's mostly stable).
Port: keep MediaStore `_ID` as `Song.id` to remain compatible with existing user data/backups.

### 2.7 Local artist images

Stored map `artistLower → imagePath`. Sources/locations (inconsistent, port carefully):
- Pref `artist_images_json` (JSON object) — **takes precedence** in `loadLocalArtistImages`.
- File `<appDocs>/artist_images.json` — read only when the pref is null/empty; then copied into the pref.
- Native fast background thread writes `getExternalFilesDir(null)/artist_images.json` — a different
  directory than Dart's `getApplicationDocumentsDirectory()` (`<dataDir>/app_flutter`), so Dart never
  reads it. **Bug.**
- MediaStore-returned and dir-scan maps are written to the file only; since the pref wins once set,
  later file updates are ignored. **Bug** (only `detectLocalArtistImages` writes both).
- Used only if setting `prefer_local_artist_images` (default true).

Algorithms (walk from song's parent dir upward, stopping after the scan root):
- A (dir scan, Dart): key `lowerArtist = song.artist.trim().lower`; `cleanArtist` = part before first
  `,` `;` `/` ` feat.` ` ft.`. In each dir that has images: first image named
  `artist.{jpg,png,webp,jpeg}` or `<cleanArtist>.{…}`; else if dir name == clean/lower artist → first
  image; else **any image in the dir** (so album cover jpgs become artist images!). Saved for both
  `lowerArtist` and `cleanArtist`.
- B (`detectLocalArtistImages`, Dart, isolate; triggered by player when map empty): per individual
  artist from `parseIndividualArtists` (skip "unknown artist") and also the raw full artist string;
  match `artist.*` or `<artistLower>.*`, else dir name == artist → first image; no "any image"
  fallback. Writes pref + file.
- C (native `resolveArtistImageFast`): per individual artist (Kotlin copy of the parser, lowercase
  fallback "unknown artist"), checks file names `artist.jpg/png/webp/jpeg`, `<lower>.jpg/png/webp/jpeg`
  in order; else dir name == artist → first `jpg/jpeg/png/webp` file. Stops when dir equals root
  (case-insensitive).
`ArtistGroup.localImagePath = map[artist.nameLower]`.

### 2.8 ReplayGain fill & concurrency

`_fillReplayGain(songs, maxWorkers=4)`: indices with `replayGainScanned == false`; workers =
`clamp(maxWorkers, 1, pending)`; chunk = ceil(pending/workers); each chunk read in its own isolate
(`ReplayGainReader.tryReadFile`). Result `failed` (IOException: can't open/read) → leave unmarked
(retry next sync); otherwise `withReplayGain(info)` (marks scanned even if no tags). Kotlin: a
`Dispatchers.IO` fan-out of 4.

Other concurrency: library (de)serialization runs in `Isolate.run`; cache writes are serialized
through a single future chain (`_libraryWrite`), and every read awaits pending writes first. Port with
a single-thread dispatcher / Mutex for the cache file.

### 2.9 Lyrics sidecar detection

`hasLyrics` = `<filePathWithoutLastExt>.lrc` exists OR `.txt` exists (exact case). Parsing §8.

---------------------------------------------------------------------------------------------------

## 3. Persistence

### 3.1 Storage backends

- Prefs: Flutter `SharedPreferencesAsync` → on Android this is **Jetpack DataStore Preferences named
  `FlutterSharedPreferences`** (`files/datastore/FlutterSharedPreferences.preferences_pb`), keys
  un-prefixed. String lists are stored as strings prefixed `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!`
  + JSON array; doubles as strings prefixed `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu` (verify with
  the plugin version if migrating real user data).
- `<appDocs>` = `getApplicationDocumentsDirectory()` = Android `<dataDir>/app_flutter/`.
- `<cache>` = `getApplicationCacheDirectory()` = `context.cacheDir`.

### 3.2 Files on disk

| Path | Format | Writer | Notes |
|---|---|---|---|
| `<appDocs>/imported_songs.json` | JSON array of Song objects (§1.1) | scanner | **Library cache.** Atomic: write `imported_songs.json.tmp` (flush) then rename. Serialized writes. |
| `<appDocs>/playlists.json` | JSON array of Playlist | scanner | Legacy *backup copy*, rewritten on every save (non-atomic, best effort). Read only if pref missing/empty. |
| `<appDocs>/artist_images.json` | JSON object `{artistLower: path}` | scanner | See §2.7. |
| `<appDocs>/listening_stats.json` | JSON object (§6.4) | StatsService | Plain overwrite with flush (not atomic); a stale `.tmp` is deleted on load. |
| `<appDocs>/saved_queue_ids.json` | JSON int array | `saveQueueIds` | **Dead code** (no callers). |
| `<appDocs>/artwork_<ms>_<id>.jpg` | raw embedded picture bytes | dir scan | Never cleaned. |
| `<cache>/album_art/art_album_<id>.jpg` / `.none` | JPEG q90 ≤720px / empty marker | native | §2.3 |
| `<cache>/thumbnails/<hash>.png` | PNG, width 128 | ThumbnailStore | §7.1 |
| `<systemTemp>/<playlistName>.m3u` | M3U | export | §4.3 |
| playlist cover files | JPEG q85 (Android) / PNG (fallback), center-cropped square ≤512 | `PlaylistImageUtils` | Path chosen by caller (UI). Old cover file deleted when replaced. |

Legacy migration of library cache: if pref `imported_songs_json` exists → (if non-empty) write it to the
file atomically, remove the pref, and return its content ("prefs copy wins"). Empty string → just removed.

iOS-only: paths containing `/Documents/` not under the current docs dir are rebased (container
changes on reinstall). Not needed on Android.

### 3.3 SharedPreferences keys (data layer + related)

Library / sync:
- `scan_folder_path` (String) — scan root path.
- `last_sync_time` (String, §2.5), `last_sync_timestamp` (int ms), `last_sync_duration_<method>`
  (int ms; method used is `last`), `last_perf_log` (String), `sync_method` (String, default
  `parallel`, effectively unused), `metadata_version` (int; 1 = current), `postpone_sync_until` (int ms).
- `imported_songs_json` (legacy library copy, migrated & removed).
- `artist_images_json` (String JSON map).
- `colorless_artwork_paths` (StringList) — artwork paths that produced no color (skip re-extraction).
- `playlists_json` (String JSON array of Playlist) — **primary playlist store**.
- `favorite_albums_map` (String JSON `{ "<albumLower>|||<artistLower>": epochMs }`), legacy
  `favorite_albums` (StringList of keys → date = now on read).
- `favorite_artists_map` (String JSON `{ "<artistLower>": epochMs }`), legacy `favorite_artists`.
- Sorting (scanner, legacy/unused by UI): `sort_by`/`sort_ascending` (default `title`/true),
  `sort_by_<tab>`/`sort_ascending_<tab>` (default songs→`title`, others→`name`; true).
- Sorting (UI, SettingsProvider): `song_sort_by` (`title`), `song_sort_ascending` (true),
  `album_sort_by` (`name`), `album_sort_ascending` (true), `artist_sort_by` (`name`),
  `artist_sort_ascending` (true), `playlist_sort_by` (`name`), `playlist_sort_ascending` (true),
  `favorites_sort_by` (`date`), `favorites_sort_ascending` (**false**), `song_activity_view` (`all`).
- Title filter: `filter_title_features` (false), `filter_title_artist` (false).
- `prefer_local_artist_images` (true), `restore_last_played_song` (true), `replay_gain_mode` (`off`).

Player persistence (§3.4): `last_played_song_id` (int), `last_played_queue_ids` (StringList of ids),
`last_played_original_queue_ids` (StringList), `last_played_position_ms` (int),
`last_played_is_shuffled` (bool). Effects: `volume` (double), `speed` (double), `mfx_preset_name`,
`mfx_preset` (double, legacy), `mfx_isSlowed`, `mfx_isSuperSlowed`, `mfx_isSpedUp`,
`mfx_isSuperSpedUp`, `mfx_isNightcore`, `mfx_isReverbEnabled`, `mfx_isLofiEnabled`,
`mfx_isBassBoostEnabled`, `mfx_isCustomEqEnabled`, `mfx_bluetoothMfxPatch` (bools),
`mfx_customEqGains` (StringList of doubles), `default_theme_color` (int ARGB).
(Full settings list: see backup whitelist §7.)

### 3.4 Player persistence (`player_persistence.dart`)

`_saveLastPlayedState` (only if a current song): writes the 5 `last_played_*` keys; position is
`player.position` at that instant. Called on: every media-item change, every playing/paused change,
app lifecycle paused/inactive/detached. `stop()` clears all 5 keys.

`restoreLastPlayedState(library)` — runs once, on the first `updateSongs` when nothing is queued:
1. Abort if setting `restore_last_played_song` false, library empty, or something already queued.
2. `last_played_song_id` must exist in library (by id) else abort.
3. Queue = saved ids that still exist (order kept, unparsable/missing dropped); empty → `[target]`.
4. Original queue likewise; empty → copy of queue.
5. index = position of saved id in queue (fallback 0). `isShuffled` restored.
6. Load into player with `initialIndex`, `initialPosition = saved ms` (not playing).

`loadSettings` (favorites maps with legacy migration; volume; speed; MFX preset resolution order:
`mfx_preset_name` → legacy `mfx_preset` speed → legacy bools priority superSlowed > slowed >
superSpedUp > spedUp > nightcore; then effect bools and EQ gains). Belongs to the playback auditor but
note it runs again after backup restore.

`updateSongs(songs)` (library refresh): rebuild album/artist snapshots; if nothing playing and first
call → restore last state; if nothing playing later → queue = whole library; if playing → keep queue
order but swap each queue item for the rescanned song with the same `filePath` (keeps stale object if
gone) and resync handler metadata. Then start background color extraction, artist image detection,
thumbnail generation and preflight.

---------------------------------------------------------------------------------------------------

## 4. Playlists, favorites, M3U

### 4.1 Playlist storage

- Read: pref `playlists_json`; if null/empty → legacy file `playlists.json` (then re-saved to pref).
  Any playlist with id `favorites` is removed and the list re-saved (old built-in Favorites playlist).
- Save: pref (strict) + best-effort file copy.
- Order = list order as stored (creation order); UI sorts (§5.4).
- Ops: create (empty, id = now ms), delete by id, add songs (dedupe against existing, append),
  remove songs (all occurrences), rename, update cover (deletes the previous cover file if different),
  update description (null clears), reorder (replace id list), create from songs (save queue as
  playlist, no dedupe).
- Playlist song ids that no longer exist in the library are kept in storage; UI filters them
  (playlist "songs" sort counts only ids present in library).

### 4.2 Favorites

- Song favorite: `isFavorite` + `favoriteDateMs` inside the library cache. Toggle sets date = now on
  favorite, null on unfavorite. Bulk `setFavoriteSongs(ids, fav)` sets the same `now` (or null).
- Album / artist favorites: separate pref maps (§3.3) keyed by lowercase group keys with ms date.
- Favorites lost on resync if a song's path changes or MediaStore drops it.

### 4.3 M3U export (`exportPlaylistToM3u`)

File `${Directory.systemTemp}/${playlist.name}.m3u` (name not sanitized — `/` in a name breaks it).
Content (each line `\n`-terminated, UTF-8):
```
#EXTM3U
#EXTINF:<durationSeconds floor>,<artist> - <title>
<absolute filePath>
...
```
Raw `title` (not displayTitle). Songs missing from the library are skipped. Returned file is shared by UI.

### 4.4 M3U import (`importM3u(file)`)

- Read all lines (UTF-8), trim; skip empty and lines starting with `#` (EXTINF ignored).
- For each path line: filename = last segment after `/` or `\`. Match = **first** library song (cache
  order) whose `filePath.endsWith(filename)` — note suffix match, so `a.mp3` also matches `ba.mp3`;
  case-sensitive; no URL decoding; relative paths fine.
- Duplicates allowed. Unmatched lines dropped.
- If ≥1 match: new playlist, id = now ms, name = file name without `.m3u`/`.m3u8` (regex `\.m3u8?$`,
  case-sensitive), description `"Imported from M3U"`. If zero matches: nothing created, silently.
- All errors swallowed.

---------------------------------------------------------------------------------------------------

## 5. Grouping & sorting

### 5.1 `parseIndividualArtists(s)`

1. trim; empty → `["Unknown Artist"]`.
2. Split by regex (case-insensitive):
   `[,;/&\\]|\s+(?:feat\.?|ft\.?|featuring|vs\.?|with|[xX\+])\s+`
   (single chars `,` `;` `/` `&` `\`, or whitespace-surrounded `feat`, `feat.`, `ft`, `ft.`,
   `featuring`, `vs`, `vs.`, `with`, `x`, `X`, `+`).
3. Each part: trim, strip leading `(`/`[`/spaces and trailing `)`/`]`/spaces, trim.
4. Drop empty; dedupe case-insensitively keeping first spelling. Empty result → `["Unknown Artist"]`.
Examples: `"Drake feat. Future & Young Thug"` → `[Drake, Future, Young Thug]`;
`"AC/DC"` → `[AC, DC]` (!); `"Simon & Garfunkel"` → two artists; `"Earth, Wind & Fire"` → three.
Native Kotlin copy returns lowercase `"unknown artist"` when empty.

### 5.2 `buildAlbumGroups(songs)` (album artist tag is NOT used — there is none)

1. For each song: `primary = parseIndividualArtists(artist).first`; `albumName = album.trim()` or
   `"Unknown Album"` if empty. If `primary == "Unknown Artist"` (exact case) OR
   `albumName.lower == "unknown album"` → bucket `"unknown album|||unknown artist"`; else bucket
   key = `albumName.lower` (**album name only**, case-insensitive, trimmed).
   Consequence: a song with a real album but unknown artist goes into the Unknown bucket and loses its
   album (test: artist `''`, album `Some Album` → "Unknown Album").
2. Unknown bucket → one group `name="Unknown Album"`, `artist = primary of first song`.
3. Other buckets: `name` = trimmed album of the **first song** in bucket (first-encountered casing).
   - `artistCounts[primaryLower]++`, `artistCased[primaryLower] = primary` (last spelling wins).
   - `commonArtists` = intersection over all songs of their lowercased individual-artist sets.
   - If `commonArtists` non-empty → artist = first element of the set (Dart `Set` keeps insertion
     order: order from the first song's parsed list after `retainAll`) displayed with the first
     song's spelling. One group.
   - else if the dominant primary (max count; ties → first encountered) has count `> songs/2` → that
     artist, one group.
   - else if only one distinct primary → that (unreachable in practice).
   - else split by primary artist (lower) into sub-groups, each `artist = primary of its first song`,
     same album name.
4. Songs inside a group sorted: disc (`null→1`) asc, then track (`null→0`) asc; if both tracks are 0 →
   `filePath` compare; else `titleLower` compare.
5. Groups sorted by `nameLower` asc (stable sort — equal names keep bucket/sub-group order).
Test expectations: "808" with artists `Ufo361, Capital Bra, Sonu Lal` / `Ufo361, Gzuz, RAF Camora` → one
album artist `Ufo361`; "Greatest Hits" by Queen and Guns N Roses → two albums.

### 5.3 `buildArtistGroups(songs, albums, localImages?)`

- One entry per individual artist (lowercase key), display name = first spelling encountered (song order).
- `songs` = all songs where the artist appears in the parsed list (a song appears under each of its artists).
- `albums` = every album group where the artist appears in any song's parsed artists or in the album's
  artist (preserves album list order).
- `localImagePath = localImages[lowerName]`.
- Sorted by `nameLower` asc.
- `buildAlbumGroup(name, artist, songs)`: rebuild all; match `nameLower==name.trim.lower` and (artist
  equal or one of album.artist's individual artists equal); else first album with that name; else empty
  group. `buildArtistGroup` likewise by nameLower; else empty group.

### 5.4 Sort options (exact semantics)

Songs (`LibrarySearchIndex.getSortedSongs/searchSongs`, also `MusicScanner.sortSongs` identical):
- `title`: `titleLower` asc.
- `artist`: `artistLower`, then `titleLower`.
- `duration`: duration, then `titleLower`.
- `recent`: `lastModifiedMs ?? 0` **descending**, then `titleLower`.
- any other value → title.
- Final tie-break `id` asc. Then the whole comparison is negated when descending (so tie-breaks
  invert too, and `recent` descending = oldest first).
- `plays` (UI only): base list sorted by title asc, then `applyActivityView(mostPlayed if !ascending
  else leastPlayed)` (§6.3). Selecting `plays` flips direction to descending; leaving it → ascending.
Albums (`searchAlbums`): `name` (nameLower), `artist` (artistLower), `tracks` (song count), `recent`
(`latestModifiedMs` desc); no secondary tie-break (stable sort keeps the grouping order, i.e. name asc);
descending negates.
Artists (`searchArtists`): `name`, `tracks` (song count), `albums` (album count). **UI offers `songs`,
which is not handled → falls back to name. Bug** — port should map `songs` → song count (or preserve).
Playlists (home screen): `name` (nameLower) or `songs` (count of ids present in library, tie → name);
descending negates.
Favorites screen (shared key `favorites_sort_by`, default `date` desc): songs: `duration`, `date`
(`favoriteDateMs ?? 0`), else title; albums: `duration` (sum ms), `date` (favorite map value ?? 0),
else nameLower; artists likewise. No tie-breaks.
Scroll section letter: first char uppercased if `[A-Z]` else `#`; duration sort shows `"${minutes}m"`;
plays shows play count.

Known bug: scanner pre-sorts the cache with `sort_by_songs` keys that the UI never writes (UI writes
`song_sort_by`), so the cached order is always title asc. Harmless because the UI re-sorts via the index.

---------------------------------------------------------------------------------------------------

## 6. Search index (`LibrarySearchIndex`)

- Built from songs list (album/artist args unused). Token index + trie are built, but semantics reduce
  to: **for every whitespace-separated term of `query.trim().toLowerCase()`, the song matches if
  `titleLower`, `artistLower` or `albumLower` contains the term (substring)**; songs must match ALL
  terms (AND). (The trie prefix pass is a subset of the substring pass. Tokenizer splits on
  `[\s\-_\.,;:/\(\)\[\]]+`.)
- No diacritic folding, no kana/width normalization, no fuzzy matching, no ranking. Results are
  ordered purely by the chosen sort (§5.4). Empty query → full sorted list (cached per
  `"${sortBy}_$ascending"`).
- Albums search: whole trimmed lowercase query is a substring of `nameLower` or `artistLower`
  (no tokenization). Artists: substring of `nameLower`. Playlists: substring of `nameLower`.
- Album/artist sorted caches are reused only if `cached.length == all.length` (stale-cache risk if the
  library changes but count doesn't; index rebuilt on `buildIndex` anyway).
- Tests: `"bohem"` → Bohemian Rhapsody; `"queen"` (artist) → 2 songs; `"opera"` (album substring);
  `"eagles calif"` → Hotel California; non-match → empty.

---------------------------------------------------------------------------------------------------

## 7. Images: thumbnails, preflight, color, playlist covers

### 7.1 ThumbnailStore

- Dir `<cache>/thumbnails`, created at startup; set of existing `*.png` loaded into memory.
- `dim = 128`. Thumbnail = decode artwork with `targetWidth=128` (height keeps aspect), encode PNG,
  write `<hash>.png.tmp` then rename.
- File name hash: two FNV-1a-style 32-bit hashes over the UTF-16 code units of the artwork path:
  `h1 = 0x811c9dc5`, `h2 = 0x01000193` (initial values), for each unit `h = ((h ^ unit) * 0x01000193)
  & 0xffffffff` for both; name = `h1.toRadixString(16) + h2.toRadixString(16)` (lowercase hex,
  **no zero padding**). Use `Long` math in Kotlin.
- Thumbnails used only when the requested cache dimension equals 128.
- Generation: sequential over unique artwork paths, skipping existing/missing files, 16 ms pause between
  each, cancellable via token.

### 7.2 In-memory image cache & preflight (Flutter-specific; map to Coil)

- Global image cache caps: **150 MB** (`150*1024*1024` bytes) and **250 images**.
- `computeCacheDim(size, dpr)`: `dpr` clamped to [1.0, 2.5]; `raw = (size*dpr).toInt()`; round **up** to
  a multiple of 128; clamp to [128, 800].
- Preflight (warm decode): songs: up to 60 unique artwork paths, size 48 (→ dim 128), batches of 6;
  albums: up to 30 (first song's artwork), size 200 (→ 512 at dpr 2), batches of 4; queue: next 5 from
  current index at size 400 (→ 800). 16 ms yield between batches; keys `"$path@$dim"` remembered.
  Cancel on new run (per-loop tokens). Run after every scan/library update.

### 7.3 Dominant color (`ColorExtractor`)

- Decode the file resized to exactly **32×32** (aspect not preserved), RGBA.
- Average R, G, B of pixels with alpha > 128 (comment says it skips dark pixels — it doesn't). Rounded.
- No counted pixels or decode error → null.
- Result opaque ARGB (`0xFF000000 | r<<16 | g<<8 | b`).
- Usage: on track change — if `song.artworkPath` null → default theme color; if `song.dominantColor`
  cached → use; if path in `colorless_artwork_paths` → default; else extract, cache into the song
  (memory + library cache via `saveDominantColor`) or add path to colorless set.
- Background pass after each library update: wait **3 s**, group songs by artwork path lacking
  `dominantColor` and not colorless, extract one image at a time with **100 ms** pauses, apply all
  results at the end and save the whole library cache once.

### 7.4 Playlist cover processing

Android native `cropAndResizeImage(sourcePath, targetPath, targetSize=512, quality=85)`: decode,
center-crop to min(w,h) square, scale to 512 if larger (filtered), JPEG q85, mkdirs. Fallback (dart:ui)
writes PNG of same geometry.

---------------------------------------------------------------------------------------------------

## 8. Lyrics (`LyricsService.parseLyricsForSong(path)`)

- Locate: `<path without last ext>.lrc`, else `.txt`; none (or no `.` in path) → null. Read lines (UTF-8).
- Timestamp regex `\[(\d+):(\d+)(?:\.(\d+))?\]` (no `:` fraction separator, no `[hh:mm:ss]`).
- For each trimmed non-empty line containing ≥1 timestamp: `hasAnyTimestamp = true`; text = line with all
  timestamps removed, trimmed; empty text → skip (no blank "instrumental" lines kept); one `LyricLine`
  per timestamp (multi-stamp lines duplicate the text). Fraction: 2 digits → ×10 ms (centiseconds),
  otherwise parsed as ms literally (`.5` → 5 ms, `.123` → 123 ms).
- Lines without timestamps are ignored when the file is synced (metadata tags like `[ti:]` dropped).
  `[offset:]` is NOT applied.
- Synced → sort by time (stable) → `isSynchronized = true`.
- Unsynced (no timestamp anywhere): every trimmed non-empty line, except those matching
  `^\[(ti|ar|al|by|offset|re|ve|length):` (case-insensitive), as `LyricLine(time=0)`;
  `isSynchronized=false`. Section headers like `[Verse 1]` kept.
- Any exception → null. No embedded (USLT) lyrics support; no online lyrics.

---------------------------------------------------------------------------------------------------

## 9. ReplayGain

### 9.1 Reader (`ReplayGainReader`, pure byte parsing; port 1:1)

Never throws. `tryReadFile`: IOException (open/read) → `failed=true`; any other error → `info=null,
failed=false`. Sniff first 12 bytes:
- `"ID3"` at 0 → ID3v2; if no RG found and the byte at tag end is `"fLaC"` → parse FLAC after it.
- `"fLaC"` at 0 → FLAC metadata blocks from offset 4.
- `"OggS"` at 0 → Ogg (Vorbis/Opus).
- `"ftyp"` at 4 → MP4.
- else null (no APE, no WMA/ASF, no WAV/RIFF, no ID3v1, no ID3 at end of file).

ID3v2: header 10 bytes; major ∈ [2,4] else invalid; size = syncsafe(6..9) (invalid if any high bit);
tagEnd = 10 + size (+10 if v2.4 footer flag 0x10). Flags: 0x80 unsync, 0x40 extended header.
- v2.2/v2.3 with tag-level unsync: read `min(size, 1 MiB)` bytes (or to EOF), de-unsync
  (`FF 00`→`FF`), parse frames from the decoded buffer.
- Extended header (v≥3): v2.4 size is syncsafe (must be ≥6, skip `size`); v2.3 skip `4 + uint32`.
- Frames: v2.2 id 3 chars/header 6 (size 24-bit BE); v2.3 id 4/header 10, size uint32 BE;
  v2.4 size syncsafe, falling back to uint32 if a high bit is set. Stop on id byte 0 (padding), id not
  `^[A-Z0-9]+$`, size ≤ 0, or frame past end; max 4096 frames.
- Only `TXXX` (`TXX` in v2.2), size ≤ 4096 bytes. v2.3 flags byte 2: `0xC0` (compressed/encrypted) →
  skip, `0x20` grouping → skip 1 byte. v2.4: `0x0C` → skip frame, `0x40` grouping +1, `0x01` data
  length indicator +4, `0x02` → frame unsync.
- TXXX payload: encoding byte (0 Latin-1, 1 UTF-16 with BOM (default LE), 2 UTF-16BE, 3 UTF-8; >3 →
  ignore); description until terminator (1 zero byte, or 2-byte aligned `00 00` for UTF-16); value until
  next terminator.
- (RVA2 / RVAD frames, iTunNORM, LAME header gain are NOT read.)

FLAC: blocks: header byte0 bit7 = last, type = low 7 bits, 24-bit BE length; type 4 =
VORBIS_COMMENT → parse and return; stop on last block or type 127; max 4096 blocks.

Ogg: first page must be `OpusHead` or `\x01vorbis`; comment packet read across subsequent pages of the
same serial (max 512 pages): must start `OpusTags` (8 bytes) or `\x03vorbis` (7 bytes). Pages of other
serials skipped. Packet boundaries/lacing are not interpreted; payloads are concatenated.

Vorbis comments: LE uint32 vendor length (skip), LE count; each comment LE length; length > 1024 →
skipped; UTF-8 (malformed allowed); split at first `=`; max 1024 comments.

MP4: find `moov` → `udta` → `meta` (if bytes 4..8 of meta's payload are not `hdlr`, skip 4-byte
full-box header) → `ilst`; iterate items (max 512 atoms per level); `----` freeform items ≤ 4096 bytes:
children `mean` / `name` (skip 4 bytes) and `data` (skip 8 bytes), UTF-8; first `data` wins; requires
name & value; if `mean` present it must be `com.apple.iTunes`. Atom size 1 → 64-bit size; size 0 → to
limit; invalid if size < header or past limit.

File source reads use a 16 KiB block cache (4 blocks).

Key handling (keys trimmed + uppercased; first valid value wins per key):
- `REPLAYGAIN_TRACK_GAIN`, `REPLAYGAIN_ALBUM_GAIN` → `parseGain`: regex `^\s*([+-]?\d+(?:[.,]\d+)?)`,
  comma → dot, must be finite and `|g| ≤ 60` dB else ignored. (`"-7.43 dB"` → −7.43.)
- `REPLAYGAIN_TRACK_PEAK`, `REPLAYGAIN_ALBUM_PEAK` → `parsePeak`: trim, comma→dot, finite, `0 < p ≤ 10`.
- Opus only: `R128_TRACK_GAIN`, `R128_ALBUM_GAIN` → int in [−32768, 32767]; gain_dB = `raw/256 + 5`
  (−23 LUFS → −18 LUFS reference). R128 wins over REPLAYGAIN_* for that slot, and when an R128 gain is
  used the corresponding peak is dropped (null). R128 keys ignored outside Opus.
- Result null unless track or album gain present.

### 9.2 Playback math (`replayGainMultiplier`, `effectiveReplayGainVolume`)

- Mode off or info null → 1.0.
- Gain: album mode → `albumGain ?? trackGain`; track mode → `trackGain ?? albumGain`. Null/non-finite → 1.0.
- Peak: `trackPeak ?? albumPeak` (in both modes).
- `m = 10^(gain/20)`; if peak > 0 and `m*peak > 1.0` → `m = 1/peak` (clipping prevention, applies even
  for negative gains when peak > 1).
- **No preamp** setting; no separate "untagged" gain.
- Final player volume = `clamp(volume * m, 0.0, 1.0)` (cannot amplify above 1.0, so positive gains are
  effectively capped at the user's volume reaching 1.0).
- Carried to the audio handler through MediaItem extras (`rg_*` keys).

---------------------------------------------------------------------------------------------------

## 10. Formatting utilities

### 10.1 `Song.displayTitle`

Settings singleton: `filterTitleFeatures` (`filter_title_features`), `filterTitleArtist`
(`filter_title_artist`).
1. Neither → `title.trim()`.
2. If features: remove all matches of `\s*[\(\[](?:feat|featuring|ft)\.?\s+[^\]\)]+[\)\]]`
   (case-insensitive) — only bracketed forms; bare `feat. X` stays.
3. If !artist filter or artist empty → `cleaned.trim()` (if empty → original `title`, untrimmed).
4. Artist filter: escape artist (whole raw artist string, case-**sensitive**); first try remove suffix
   `\s*[—–-]\s*<artist>\s*$`; if nothing changed, remove prefix `^\s*<artist>\s*[—–-]\s*`. Result trimmed;
   if empty → `cleaned.trim()`.
Test: title `"Cosmos - Starlight (feat. Stellar)"`, artist `Cosmos`, both on → `"Starlight"`.
`displayTitle` is used for MediaItem titles (notification) and UI; raw title elsewhere (M3U, search, sort).

### 10.2 Durations

- `Song.durationFormatted`: `m:ss` (§1.1).
- `formatTotalDuration(songs)`: sum; empty → `"0m 0s"`; parts: `"${d}d"` if days>0, `"${h}h"` if
  hours%24 > 0, `"${m}m"` if minutes%60 > 0 OR (days==0 && hours==0), always `"${s}s"`; joined by space.
  Examples: 3600 s → `"1h 0s"` (minutes omitted when 0 and hours/days present); 90 s → `"1m 30s"`;
  5 s → `"0m 5s"`; 1 d 2 h 3 m 4 s → `"1d 2h 3m 4s"`; 1 d 0 h 5 m → `"1d 5m 0s"`.

### 10.3 Logger: debug-only `dart:developer.log` with prefix `[SONORA | 道]` — no data impact.

---------------------------------------------------------------------------------------------------

## 11. Listening statistics (`StatsService`)

### 11.1 What is recorded & when (driven by `PlayerProvider`)

- A "listen session" is per song playthrough; reset on media-item change to a different song, on
  `autoAdvance` discontinuity (next track or repeat-one loop), and on any seek that lands < 3 s
  (counts as listening again). A one-shot timer is armed for the earliest pending milestone:
  `(milestone - position) / speed`; re-armed on play/pause, seek, speed change, discontinuity.
  Only counts while playing; 500 ms tolerance at check time.
- **Recent play** milestone = `qualifyingRecentPlayThreshold(duration)` (25% clamped 10–30 s, ≤ duration)
  → `recordSongPlayed(id)` → `songLastPlayedAtMs[id] = now`.
- **Complete listen** milestone = **80% of duration** (position-based, not accumulated time — seeking
  forward past 80% while playing counts) → `recordCompleteListen(id, playlistId = current playlist
  context)`: `songPlayCounts[id]++`, `completeSongListens++`, `weeklyPlayCounts[now.weekday % 7]++`
  (Sun=0, Mon=1…Sat=6), first ever play sets `firstPlayedSongId` and `firstPlayedDate` =
  local `yyyy-MM-dd`; if playlistId → `playlistPlayCounts[pid]++`. Songs with duration ≤ 0 never count.
- **Skip**: `next()` when not on the last queue item → `songSkipCounts[current]++` (regardless of position).
- **Restart**: `previous()` when position > 3 s, or `seek(<3 s)` from position > 3 s →
  `songRestartCounts[current]++`.
- **Shuffle session start**: when shuffle is turned on (`player_queue.dart`) → `shuffleSessionStarts++`.
- `pauseRecording()/resumeRecording()` (nesting counter) disable all recording (used during restore).

### 11.2 Computed stats

- `completeSongListens`, `shuffleSessionStarts`, `totalSkips` (sum), `totalRestarts` (sum),
  per-song play/skip/restart counts, `songLastPlayedAt`.
- `totalUniqueSongsPlayed` = number of song ids with a play count (includes ids no longer in library).
- `playlistListenCount` = number of playlists with plays (including deleted ones).
- `albumListenCount(library)` = distinct `"nameLower|||artistLower"` album groups containing a played song.
- `artistListenCount(library)` = distinct individual artists (lower) of played songs in library.
- `firstPlayedSong(library)`, `firstPlayedDate`.
- `mostActiveDay`: `"—"` if none; else max weekly count (ties: earliest inserted key wins), labels
  `Sun Mon Tue Wed Thu Fri Sat` (English, not localized here).
- `topSongs(limit, library)`: play counts desc (stable over map insertion order), only songs in library.
- `topAlbums(limit)`: sum of song play counts per album group (identity), desc; artwork = first song in
  group with non-empty artworkPath.
- `topArtists(limit)`: per individual artist (lower), sum of song plays; display = first spelling seen;
  group looked up by nameLower in (cached) artist groups or rebuilt.
- `topPlaylists(limit, playlists)`: play counts desc, only existing playlists.
- `recentSongs(library, limit?)`: library songs with a last-played timestamp, newest first; ties →
  `_compareSongs` (titleLower, artistLower, id).
- `applyActivityView(songs, view)`: `all` → unchanged; `recentlyPlayed` → filter to songs with timestamp,
  sort newest first; `mostPlayed` → count desc; `leastPlayed` → count asc (never-played = 0 first);
  ties → `_compareSongs`.

### 11.3 Persistence & reset

- File `<appDocs>/listening_stats.json`, loaded lazily once (`ensureLoaded`); load error → all zero.
- Writes debounced **30 s** after the last change (`_markDirty`); `flush()` on app pause/inactive/detach,
  on player stop paths and dispose. Saves are coalesced (one in flight + one pending pass).
- `reset()` clears everything and saves immediately.

### 11.4 JSON schema (`listening_stats.json` and backup `stats`)

```json
{
  "complete_song_listens": 0,
  "first_played_song_id": -1,
  "first_played_date": null,            // "yyyy-MM-dd" local
  "song_play_counts": {"<songId>": n},
  "playlist_play_counts": {"<playlistId>": n},
  "weekly_play_counts": {"0".."6": n},  // 0 = Sunday
  "shuffle_session_starts": 0,
  "song_skip_counts": {"<songId>": n},
  "song_restart_counts": {"<songId>": n},
  "song_last_played_at_ms": {"<songId>": epochMs}   // entries with non-int or <=0 dropped on load
}
```
Map keys are strings (ints parsed with `int.parse` — a bad key throws → whole load treated as empty).

---------------------------------------------------------------------------------------------------

## 12. Backup / restore (`BackupService`)

### 12.1 File

- Suggested name `sonora-backup-YYYY-MM-DD.json`. Pretty-printed JSON, 2-space indent.
- Decode: strict UTF-8 (malformed → error before parse), leading BOM `U+FEFF` stripped (also in `parse`).

### 12.2 Schema (version 1)

```json
{
  "format": "sonora-backup",
  "version": 1,
  "createdAt": "2026-03-07T12:34:56.789Z",   // UTC ISO-8601 (Dart may emit 6 fractional digits)
  "appVersion": "1.25.1+1",
  "songs": [ {"id": 1, "path": "/storage/…/one.mp3", "title": "", "artist": "", "album": "", "durationMs": 0} ],
  "settings": { "<whitelisted key>": value },
  "playlists": [ {"id": "p1", "name": "Mix", "description": null, "songIds": [1, 2]} ],
  "favorites": {
    "songs": [ {"id": 1, "favoriteDateMs": 1000} ],
    "albums": { "<albumLower>|||<artistLower>": epochMs },
    "artists": { "<artistLower>": epochMs }
  },
  "stats": { …same shape as §11.4… }
}
```
Export details:
- Library empty → `libraryEmpty` error. Library/playlist read failure → `readFailed`.
- `songs` contains only songs referenced by playlists, favorites or stats, in first-reference order
  (playlists → favorites → stats maps), keyed by device-local id.
- Playlist `songIds` filtered to ids present in library. Cover images NOT exported.
- Favorites albums/artists read from `*_map` pref, else legacy list (date = now).
- Stats snapshot: song-keyed maps (`song_play_counts`, `song_skip_counts`, `song_restart_counts`,
  `song_last_played_at_ms`) filtered to library ids; `first_played_song_id` → −1 if not in library;
  `weekly_play_counts` and `playlist_play_counts` kept as is.
- Settings: whitelist only, read with typed getter; missing or wrong-typed → omitted.

Settings whitelist (key → type):
- Appearance: `theme_mode` S, `use_dynamic_theme` B, `use_material_you` B, `amoled_dark` B,
  `blurred_backgrounds` B, `dynamic_theme_color` I, `default_theme_color` I, `now_playing_style` S,
  `show_visualizer` B, `immersive_mode` B, `prefer_local_artist_images` B, `app_locale` S,
  `default_start_page` I, `user_name` S, `use_greeting_title` B.
- Sleep timer: `sleep_timer_default_minutes` I, `sleep_timer_fade_out_seconds` I, `sleep_timer_finish_song` B.
- Sorting: `song_sort_by` S, `song_sort_ascending` B, `album_sort_by` S, `album_sort_ascending` B,
  `artist_sort_by` S, `artist_sort_ascending` B, `playlist_sort_by` S, `playlist_sort_ascending` B,
  `favorites_sort_by` S, `favorites_sort_ascending` B, `song_activity_view` S, `sort_by` S,
  `sort_ascending` B, `sort_by_songs` S, `sort_ascending_songs` B.
- Behavior: `keep_playing_on_close` B, `restore_last_played_song` B, `pause_on_duck` B,
  `replay_gain_mode` S, `pause_on_disconnect` B, `resume_on_connect` B, `filter_title_features` B,
  `filter_title_artist` B, `auto_check_updates` B.
- Playback/effects: `volume` D, `speed` D, `mfx_preset_name` S, `mfx_preset` D, `mfx_isSlowed` B,
  `mfx_isSuperSlowed` B, `mfx_isSpedUp` B, `mfx_isSuperSpedUp` B, `mfx_isNightcore` B,
  `mfx_isReverbEnabled` B, `mfx_isLofiEnabled` B, `mfx_isBassBoostEnabled` B, `mfx_isCustomEqEnabled` B,
  `mfx_bluetoothMfxPatch` B, `mfx_customEqGains` StringList, `eq_current_preset` S,
  `eq_custom_gains` StringList, `eq_enabled` B.
Excluded by design: scan folder, sync bookkeeping, caches, last-played state, onboarding flags,
artist images, colorless list, library cache itself.

### 12.3 Parse & validation (nothing written on failure)

- Not JSON → `invalidJson`. Not an object or `format != "sonora-backup"` → `notABackup`.
  `version != 1` (exact int) → `unsupportedVersion`. Bad `createdAt` → `malformed`.
- `songs` (default `[]`): each must have int `id`, `durationMs`; string `path`,`title`,`artist`,`album`.
- `settings` optional: unknown keys or null values ignored; known key with wrong type → `malformed`
  (decimal accepts any number; ints are NOT accepted for booleans etc.).
- `playlists` optional: id/name strings, description null or string, songIds list of ints.
- `favorites` optional: `songs` list of `{id:int, favoriteDateMs:int|null}`; `albums`/`artists`
  objects of int values (default `{}`).
- `stats` optional: ints default (0, −1, 0), `first_played_date` null|string; id-keyed maps need
  int-parsable keys and int values.
- Error kinds: `invalidJson, notABackup, unsupportedVersion, malformed, libraryEmpty, noMatchingSongs,
  readFailed, writeFailed`.

### 12.4 Song matching (`matchSongs`, one-to-one)

Refs and library both sorted by id asc. Indices: `byPath` (exact path → first song), `byTags`
(`trim.lower` title + `\0` + artist + `\0` + album → songs), `byTail` (last 2 path segments after
`\`→`/`, empties removed, lowercased). Three passes over refs (skipping already-matched refs); a library
song can be claimed once (a later ref that lands on a claimed song in pass 1 stays unmatched in that pass):
1. exact `path`.
2. same tag key, unclaimed, **|duration diff| ≤ 2000 ms**, closest duration; ties → lowest library id.
3. same path tail AND (`title` equal OR `artist` equal, trim+lower), closest duration within 2000 ms.

### 12.5 Restore semantics (`restoreDocument`)

All stats recording paused during restore. Plan phase (no writes):
- Library empty → `libraryEmpty`. Compute `idMap`. `referenced` = playlist song ids ∪ favorite song ids
  ∪ stats song ids (incl. first played). If non-empty and none matched → `noMatchingSongs`.
  `unmatchedSongs` = count of referenced ids without a match.
- Settings: every backed-up setting overwrites; if the file had a `settings` section, keys
  `mfx_preset_name`, `mfx_preset`, `mfx_customEqGains` absent from it are **removed** ("no effects").
  Other existing prefs untouched.
- Favorite albums/artists: merged `{...backup, ...current}` (existing entries keep their dates);
  written only if size changed or the `_map` pref was missing and merged non-empty.
- Playlists (only if backup has ≥1): merged by id into current list; backup wins (name, description,
  remapped ids — unmatched dropped, duplicates removed); existing cover kept; new playlists appended
  with no cover. Local-only playlists untouched.
- Favorite songs: union — songs not yet favorite become favorite with backup date (`?? now`);
  already-favorite keep their date. Never un-favorites.
- Stats: **replaced entirely** with remapped backup stats (song-keyed maps remapped; collisions summed,
  last-played → max); weekly & playlist counts copied; current device stats discarded.
- Idempotent (restoring twice = same state).
Write phase with rollback stack (prefs → playlists → library favorites → stats); any failure rolls
back
in reverse (prefs restored/removed; playlists re-saved or cleared if there were none; library rewritten;
stats snapshot replaced) and throws `writeFailed`.
Result: `{playlistsRestored, favoriteSongsRestored (matched favorite entries), favoriteAlbumsAndArtistsRestored
(count in backup), unmatchedSongs, settingsRestored (count), statsRestored, songs}`. Then the player
calls `reloadAfterRestore(songs)`: swaps song objects by id (keeps order), reloads playlists, rebuilds
snapshots, `loadSettings()`.

---------------------------------------------------------------------------------------------------

## 13. Edge cases, bugs & surprises (decide: replicate or fix)

1. **Song ids = MediaStore `_ID`** on Android; stats/playlists/favorites/backups depend on it.
2. **Artist splitting is aggressive**: `/`, `&`, ` x `, ` with ` split names (`AC/DC`, `Simon & Garfunkel`,
   `Tyler, The Creator`). Affects artists tab, album-artist resolution, stats, artist images.
3. **No album-artist tag** support; album grouping by album *name* only (case-insensitive), then
   artist heuristics. Two different "Greatest Hits" albums merge if they share an artist anywhere.
4. Songs with unknown artist lose their album (bucketed into "Unknown Album").
5. MediaStore path: `trackNumber` is raw `TRACK` (disc×1000+track), 0 instead of null; `year` 0;
   `discNumber`, `genre`, `bitrate`, `samplerate` always null. Album sort therefore uses TRACK only
   (multi-disc still orders correctly by the ×1000 encoding).
6. Unchanged-file check ignores a MediaStore artwork change to null; art extracted in background only
   appears on the *next* sync.
7. Artist-image map: pref `artist_images_json` shadows the file forever; native background writes to
   `getExternalFilesDir` (never read). Directory-scan variant uses "any image in dir" fallback.
8. Dir scan: favorites date and dominant color lost when a file is re-parsed; embedded art files leak
   (`artwork_<ms>_<id>.jpg` never deleted); `metadata_version<1` ignores the cache (loses favorites).
9. `syncLibraryLegacy` returns `[]` on any exception → app shows empty library (cache intact).
10. Folder missing → sync returns `[]` (UI empty) but cache preserved.
11. M3U import matches by filename suffix (`endsWith`) and first match wins; export filename unsanitized.
12. Search: plain lowercase substring AND; no accent/kana folding; sort applies after filtering; no relevance.
13. Artist sort option `songs` not implemented in index (falls back to name). Scanner pre-sort uses keys
    the UI never writes.
14. `recent` sort = file mtime (not date added); descending direction flips all tie-breaks.
15. Complete listen = position ≥ 80% while playing (seeking counts); recent = 25% clamped 10–30 s.
    Skip counted on every "next" except at end of queue (even at 95%).
16. Stats write not atomic; malformed key → all stats reset to zero on load.
17. `favoriteDateMs` missing in cache with `is_favorite` → now() at every decode until rewritten.
18. Library cache decode failure → treated as empty by most callers (but backup uses strict read and
    reports `readFailed`).
19. Color extraction averages all opaque pixels (no vibrancy / dark filtering despite comment).
20. `formatTotalDuration` omits minutes when hours/days present and minutes==0; always shows seconds.
21. Lyrics: `.5` fraction = 5 ms (not 500); `[offset:]` ignored; blank timed lines dropped;
    only exact-case sidecar names.
22. ReplayGain: no preamp, no APE/WMA/WAV, ID3 at file start only; volume capped at 1.0 so positive gain
    can't amplify; clipping guard uses track peak even in album mode.
23. `Song`/`Playlist`/groups use identity equality — port with `data class` carefully (AlbumGroup as map
    key in topAlbums; Song lists compared by id).
24. Timestamps & ids from `DateTime.now()` (playlist id = ms string → collision possible if created in the
    same ms, e.g. import + create quickly).
25. `last_sync_time` string is English-only, 12h format.
26. Album group `name` comes from the first song's casing; `artistCased` uses the last-seen spelling.
27. Album/artist sorted-list cache in the search index validated only by length.
