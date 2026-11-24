# Smart shuffle, weekly mix and LAN Jam Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a smart shuffle setting, a weekly 50-song mix that refreshes Monday 08:00, and a LAN Jam where several phones play the same queue from their own files.

**Architecture:** Pure, tested logic lives in `shared/commonMain` (`SmartShuffle`, `WeeklyMix`, Jam protocol and clock math). Android wiring (service, DataStore, Compose screens, sockets) lives in `androidApp` and `shared/androidMain`. Jam reuses device sync's `JpakeHandshake`, `PairingCode` and `SecureChannel`. Three parts, each its own PR, in order.

**Tech Stack:** Kotlin Multiplatform, Compose, Media3, Room, DataStore, kotlinx.serialization, BouncyCastle (existing), Android NSD.

**Spec:** `docs/superpowers/specs/2026-11-23-smart-shuffle-weekly-mix-jam-design.md`

## Global Constraints

- Everything runs on the device; no new network destination, no analytics.
- Smart shuffle setting is `smart_shuffle`, default **false**; with it off, shuffle behaves exactly as today.
- Weekly mix: up to 50 songs, at most 3 per artist (relaxed when the library is too small), refresh Monday 08:00 local time, computed in local calendar time (no `+7 * 24 h`).
- Jam: up to 7 guests, fresh 6-digit code per Jam, no QR, no camera permission, never change a guest's speed or pitch, no mid-song jumps; one seek only if drift is over 2 s, at the next pause or song change.
- All user text in string resources, English (`values/`) and Japanese (`values-ja/`), sentence case; product name is always "SonoraKMP" (`scripts/check_product_name.sh`).
- Pure logic goes in `shared/commonMain` with tests in `shared/commonTest`; tests that need BouncyCastle or sockets go in `shared/androidHostTest`.
- Commits follow Conventional Commits; one PR per part; do not open a PR without being asked.
- Verify with: `./gradlew :shared:testAndroidHostTest testDebugUnitTest lintDebug`.

## Review Focus

- Library with fewer than 50 songs, or empty: weekly mix is shorter or empty with an empty state (Task 5).
- Clock set backwards, time-zone change, or DST week: weekly mix regenerates and the countdown never goes negative (Tasks 4, 6).
- Songs deleted mid-week: list drops them, no crash, no refill (Task 5).
- Wrong Jam code three times: guest is locked out; a full Jam (8th guest) is refused with a clear error (Tasks 10, 11).
- Host disappears mid-song: guests finish the current song and then show "Jam ended" (Task 12).

---

## Part 1: Smart shuffle

### Task 1: Smart shuffle algorithm

**Files:**
- Create: `shared/src/commonMain/kotlin/de/yurtemre/sonora/shared/queue/SmartShuffle.kt`
- Test: `shared/src/commonTest/kotlin/de/yurtemre/sonora/shared/queue/SmartShuffleTest.kt`

**Interfaces:**
- Consumes: `Song`, `SongStats(plays, skips, restarts, lastPlayedAtMs)` from `repo/api/StatsRepository.kt`.
- Produces: `object SmartShuffle { fun order(songs: List<Song>, stats: Map<Long, SongStats>, nowMs: Long, random: Random = Random): List<Song> }`; `fun smartWeight(song: Song, stats: SongStats?, nowMs: Long): Double` (public for tests).

- [ ] **Step 1: Write failing tests** in `SmartShuffleTest`:
  - `weightDefaultsToOneWithoutStats`: `smartWeight(song, null, now) == 1.5` (fewer than 2 plays) and a song with 5 plays and no skips weighs `1.0`.
  - `skipHeavySongsWeighLess`: 2 plays + 4 skips (rate 0.67, 6 events) gives `0.3`; 1 skip only gives no penalty (fewer than 3 events).
  - `recentlyPlayedWeighsHalf`: `lastPlayedAtMs = now - 1h` multiplies by `0.5`.
  - `favouriteWeighsOneAndHalf`: `isFavorite = true` multiplies by `1.5`.
  - `orderKeepsAllSongsExactlyOnce` and `orderIsPlainShuffleWhenAllWeightsEqual` (seeded `Random`, same multiset).
  - `orderAvoidsSameArtistBackToBack`: three songs by A and two by B → no adjacent equal artists in the result.
  - `orderHandlesEmptyAndSingle`: returns input unchanged.
  - `skippedSongsLandLaterOnAverage`: over 500 seeded runs, a song with weight 0.3 has a larger mean index than one with weight 1.5.
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests '*SmartShuffleTest*'`; expect FAIL (unresolved reference).
- [ ] **Step 3: Implement** `SmartShuffle` with the weights from the spec (skip-heavy ×0.3 when skips/(plays+skips) ≥ 0.5 and plays+skips ≥ 3; played in the last 24 h ×0.5; favourite ×1.5; plays < 2 ×1.5). Ordering: Efraimidis-Spirakis keys `u^(1/w)` sorted descending; then one forward pass that swaps in a later song by a different artist (within the next 5) when two adjacent share an artist.
- [ ] **Step 4: Run** the same command; expect PASS.
- [ ] **Step 5: Commit** `feat(shuffle): weighted smart shuffle ordering`.

### Task 2: Setting, wiring and UI toggle

**Files:**
- Modify: `shared/.../settings/SettingsKeys.kt` (add `SMART_SHUFFLE = booleanPreferencesKey("smart_shuffle")`), `shared/.../model/AppSettings.kt` (`val smartShuffle: Boolean = false`), `shared/.../settings/SettingsRepository.kt` (`suspend fun setSmartShuffle(v: Boolean)`, and map it in the settings read near line 173), `shared/.../backup/SettingsWhitelist.kt`, `shared/.../queue/Shuffle.kt`, `androidApp/.../playback/SonoraPlaybackService.kt` (`handle`: `SET_QUEUE` and `toggleShuffle`), `androidApp/.../ui/screens/settings/playback/SettingsPlaybackRoute.kt`, both `strings_settings.xml` files.
- Test: `shared/src/commonTest/.../queue/ShuffleTest.kt` (extend or create), `shared/src/androidHostTest/.../SettingsRepository` tests if present for the default.

**Interfaces:**
- Consumes: `SmartShuffle.order` (Task 1).
- Produces: `QueueState.shuffleOn(random: Random = Random, reorder: ((List<QueueEntry>) -> List<QueueEntry>)? = null)` and `QueueState.quickShuffle(songIds, random, playlistContext, reorder = null)`; when `reorder` is non-null it replaces the plain shuffle of the upcoming entries.

- [ ] **Step 1: Write failing tests**: `shuffleOnUsesReorderWhenGiven` (reorder = reverse → upcoming reversed, history and current untouched); `shuffleOnWithoutReorderUnchanged` (seeded `Random` matches existing behaviour); `quickShuffleUsesReorder`.
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests '*ShuffleTest*'`; expect FAIL.
- [ ] **Step 3: Add the `reorder` parameter** to `shuffleOn`, `toggleShuffle` and `quickShuffle`. In the service, when `settings.smartShuffle` is true build `reorder` from `SmartShuffle.order` using the current library songs and `StatsRepository.snapshot.first()`; otherwise pass `null`.
- [ ] **Step 4: Add the key, field, setter, whitelist entry and a switch row** in Settings → Playback after "Skip silence", with strings `settings_smart_shuffle` ("Smart shuffle") and `settings_smart_shuffle_subtitle` ("Plays songs you skip less, favourites more, and avoids the same artist twice in a row"), plus Japanese.
- [ ] **Step 5: Run** `./gradlew :shared:testAndroidHostTest testDebugUnitTest lintDebug`; expect PASS. Update `docs/FEATURE_IDEAS.md`. Commit `feat(settings): smart shuffle toggle`.

---

## Part 2: Weekly mix

### Task 3: Week boundaries

**Files:**
- Create: `shared/src/commonMain/kotlin/de/yurtemre/sonora/shared/library/WeeklyMix.kt`
- Test: `shared/src/commonTest/kotlin/de/yurtemre/sonora/shared/library/WeeklyMixTest.kt`

**Interfaces:**
- Produces: `object WeeklyMix { fun weekStart(nowMs: Long, zone: TimeZone): Long; fun nextRefresh(nowMs: Long, zone: TimeZone): Long }` using `kotlinx-datetime` (add to `shared/commonMain` dependencies in `gradle/libs.versions.toml` if absent).

- [ ] **Step 1: Write failing tests** with fixed `TimeZone.of("Europe/Berlin")`: Monday 07:59 → `weekStart` is the previous Monday 08:00; Monday 08:00 exactly → that instant; Sunday 23:59 → the Monday before; `nextRefresh` is always `> nowMs` and exactly one local week after `weekStart`; across the 2026-03-29 DST change `nextRefresh - weekStart` is `7 days - 1 hour` in ms and both land on 08:00 local; a zone change (Berlin vs Tokyo) yields different values.
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests '*WeeklyMixTest*'`; expect FAIL.
- [ ] **Step 3: Implement** with `LocalDateTime` arithmetic (find the latest Monday 08:00 not after now, then `toInstant(zone)`).
- [ ] **Step 4: Run**; expect PASS. **Step 5: Commit** `feat(weekly): week boundaries in local time`.

### Task 4: Weekly mix builder

**Files:** Modify `WeeklyMix.kt`; Test: `WeeklyMixTest.kt`.

**Interfaces:**
- Consumes: `Song.dateAddedMs`, `SongStats`.
- Produces: `fun WeeklyMix.build(songs: List<Song>, stats: Map<Long, SongStats>, nowMs: Long, random: Random = Random, limit: Int = 50): List<Song>`; `fun WeeklyMix.likedScore(song: Song, stats: SongStats?, nowMs: Long): Double`.

- [ ] **Step 1: Write failing tests**: `likedScoreRisesWithPlaysAndFavourite` (`plays + 2` for a favourite); `likedScoreHalvesEvery30Days`; `likedScoreHalvedWhenSkipRateAtLeastHalf`; `buildNeverExceedsLimit`; `buildHasNoDuplicates`; `buildCapsThreePerArtist` (library of 100 songs by 2 artists + 10 by others gives at most 3 each until others run out); `buildRelaxesArtistCapWhenLibraryTooSmall` (30 songs, 1 artist returns 30); `buildIncludesFreshSongs` (songs added within 30 days or played at most once make up about 30%: between 10 and 20 of 50 for a large library); `buildReturnsShorterListForSmallLibrary`; `buildEmptyLibraryGivesEmpty`; `buildIsDeterministicForSeededRandom`.
- [ ] **Step 2: Run**; expect FAIL. **Step 3: Implement** — pick about 60% (30) by weighted draw on `likedScore`, about 30% (15) by weighted draw from the "fresh" set, fill to the limit from the rest, enforcing the artist cap while picking and relaxing it only when candidates run out; shuffle the final order. **Step 4: Run**; expect PASS. **Step 5: Commit** `feat(weekly): build the weekly mix`.

### Task 5: Persistence and repository

**Files:**
- Create: `shared/src/commonMain/kotlin/de/yurtemre/sonora/shared/library/WeeklyMixRepository.kt` (interface and a DataStore-backed implementation following the pattern in `PlaybackStateStore.kt`); register in `shared/src/androidMain/.../di/SharedModule.kt`.
- Test: `shared/src/commonTest/.../library/WeeklyMixRepositoryTest.kt` with an in-memory fake store and a fake `Clock`.

**Interfaces:**
- Consumes: `WeeklyMix.weekStart`, `build`, `Clock.nowMs()`, `LibraryRepository` songs, `StatsRepository.snapshot`.
- Produces: `data class WeeklyMixState(val weekStartMs: Long, val songs: List<Song>, val nextRefreshMs: Long)`; `interface WeeklyMixRepository { fun state(zone: TimeZone): Flow<WeeklyMixState>; suspend fun refreshIfNeeded(zone: TimeZone): Boolean; suspend fun saveAsPlaylist(name: String): Playlist }`. Stored value is `{weekStartMs, songPaths[]}`.

- [ ] **Step 1: Write failing tests**: `generatesOnFirstUse`; `staysStableWithinTheWeek` (stats change, second `refreshIfNeeded` returns false, same list); `regeneratesAfterMondayEight`; `regeneratesWhenClockMovesBack` (stored `weekStartMs` differs from computed); `dropsDeletedSongsWithoutRefilling`; `mapsPathsToCurrentSongIdsAfterReindex`; `emptyLibraryGivesEmptyState`; `saveAsPlaylistCopiesCurrentSongsAndStaysFixed` (later refresh does not change the saved playlist).
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests '*WeeklyMixRepositoryTest*'`; expect FAIL. **Step 3: Implement** the repository; read-time filtering by path; `saveAsPlaylist` calls `PlaylistRepository.create(name, ids)`. **Step 4: Run**; expect PASS. **Step 5: Commit** `feat(weekly): persist the weekly mix per week`.

### Task 6: Weekly mix UI

**Files:**
- Create: `androidApp/.../ui/screens/weekly/WeeklyMixRoute.kt`, `WeeklyMixViewModel.kt`, `ui/screens/home/tabs/WeeklyMixCard.kt`
- Modify: `ui/navigation/NavKeys.kt` (`@Serializable data object WeeklyMix : NavKey`), `SonoraAppShell.kt` (`entry<WeeklyMix>`), `PlaylistsTab.kt` (card above the smart-list row), `strings_*.xml` (English and Japanese)
- Test: `androidApp/src/test/.../WeeklyMixViewModelTest.kt`, and a pure `formatCountdown` test.

**Interfaces:**
- Consumes: `WeeklyMixRepository` (Task 5), `PlaybackConnection.playSong/playShuffled/addToQueue`.
- Produces: `fun formatCountdown(remainingMs: Long): CountdownParts(days, hours, minutes)` in `ui/format/`, never negative.

- [ ] **Step 1: Write failing tests**: `countdownBreaksDownDaysHoursMinutes`; `countdownClampsNegativeToZero`; `viewModelRefreshesInPlaceWhenCountdownHitsZero` (fake clock jumps past `nextRefreshMs`, state's `weekStartMs` changes); `viewModelTickerStopsWhenNotCollected`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*WeeklyMix*'`; expect FAIL.
- [ ] **Step 3: Implement** the view model with a once-a-minute ticker via `stateIn(WhileSubscribed)`, `refreshIfNeeded` on start and on resume; the card shows cover collage, song count and "Refreshes in 3 d 4 h"; the screen has play, shuffle, add to queue, a song list, "Save as playlist" (name "Weekly mix · <localized short date>"), and the empty state "Play a few songs and your weekly mix will fill up."
- [ ] **Step 4: Run** `./gradlew :shared:testAndroidHostTest testDebugUnitTest lintDebug`; expect PASS. Update `docs/FEATURE_IDEAS.md` and README feature list. **Step 5: Commit** `feat(weekly): weekly mix card, screen and countdown`.

---

## Part 3: Jam

Start with the spike; everything after depends on its result.

### Task 7: Timed-start spike (throwaway, labelled)

**Files:** Create `androidApp/src/debug/.../JamSpike.kt` (debug source set only, not merged).

- [ ] **Step 1:** Build a debug-only screen: one phone is host, one guest over device-sync pairing; host sends `play at T` (host clock), guest starts ExoPlayer at the corresponding local time. Log the measured start gap with `System.nanoTime` pings.
- [ ] **Step 2:** On two real phones record the gap on built-in speakers over 20 starts. Write the numbers (median, worst) into `docs/superpowers/specs/` as a short "spike result" note.
- [ ] **Step 3:** If the median gap is under about 500 ms, continue. If not, stop and bring the result to the user before Task 8. Do not merge spike code.

### Task 8: Jam protocol and clock math (shared)

**Files:**
- Create: `shared/src/commonMain/kotlin/de/yurtemre/sonora/shared/jam/JamModels.kt`, `JamFrameCodec.kt`, `ClockSync.kt`, `JamSongMatcher.kt`
- Test: `shared/src/commonTest/kotlin/de/yurtemre/sonora/shared/jam/*Test.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class JamSong(title, artist, album, durationMs)`; `@Serializable data class JamState(queue: List<JamSong>, index: Int, playing: Boolean, positionMs: Long, hostTimeMs: Long, guestsCanAdd: Boolean, guestsCanControl: Boolean)`.
  - Frames: `JamHello(protocol: Int, name: String)`, `JamStateFrame(JamState)`, `JamAddRequest(song: JamSong)`, `JamControl(action: JamAction)` with `enum JamAction { Play, Pause, Next, Previous }`, `Ping(id: Long, sentMs: Long)`, `Pong(id: Long, sentMs: Long, hostMs: Long)`, `JamEnd(reason: String)`.
  - `JamFrameCodec.encode(Frame): ByteArray` / `decode(type: Int, payload: ByteArray): JamFrame` in the same type+length layout as `FrameCodec`, with its own type numbers and the same `MAX_CONTROL` size guard.
  - `class ClockSync { fun addSample(sentMs: Long, hostMs: Long, receivedMs: Long); fun offsetMs(): Long?; fun toLocal(hostTimeMs: Long): Long }` using the lowest-RTT of the first 8 samples.
  - `JamSongMatcher.match(jam: JamSong, library: List<Song>): Song?` (reuse `backup/SongMatcher` rules).

- [ ] **Step 1: Write failing tests**: codec round-trips every frame; oversized and unknown-type frames throw `DeviceSyncProtocolException`; `ClockSync` returns the offset of the lowest-RTT sample with injected samples, `null` before any sample, and ignores a negative RTT; `toLocal` is `hostTime - offset`; matcher finds a song by title+artist+duration within 2 s tolerance and returns `null` when missing.
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests '*jam*'`; expect FAIL. **Step 3: Implement.** **Step 4: Run**; expect PASS. **Step 5: Commit** `feat(jam): protocol, clock sync and song matching`.

### Task 9: Session reducer (shared, pure)

**Files:** Create `shared/.../jam/JamSession.kt`; Test `JamSessionTest.kt`.

**Interfaces:**
- Consumes: Task 8 models.
- Produces: `data class JamHostState(guests: Map<String, GuestInfo>, state: JamState, settings: JamSettings)`; `fun JamHostState.apply(event: JamEvent): JamHostState` for `GuestJoined(id, name)`, `GuestLeft(id)`, `AddRequest(id, song)`, `Control(id, action)`, `HostAction(...)`; permissions enforced here (`guestsCanAdd`, `guestsCanControl`), cap `MAX_GUESTS = 7`.
- Guest side: `fun planPlayback(state: JamState, library: List<Song>, clock: ClockSync, nowMs: Long): GuestPlan` returning the matched current song or `Skip` for a missing one, the local start time, and `seekOnNextBoundary: Boolean` when drift exceeds 2000 ms.

- [ ] **Step 1: Write failing tests**: eighth guest refused (`JamError.Full`); add request ignored when `guestsCanAdd = false`; control ignored when `guestsCanControl = false`; guest left removes it; a song missing on the guest gives `Skip`; drift of 1900 ms gives no seek, 2100 ms gives `seekOnNextBoundary`; no event ever produces a speed or pitch value (the plan type has no such field).
- [ ] **Step 2: Run**; expect FAIL. **Step 3: Implement.** **Step 4: Run**; expect PASS. **Step 5: Commit** `feat(jam): host and guest session logic`.

### Task 10: Jam server and client (Android)

**Files:**
- Create: `shared/src/androidMain/kotlin/de/yurtemre/sonora/shared/jam/JamServer.kt`, `JamClient.kt`; `androidApp/.../jam/JamDiscovery.kt` (copy the `DeviceSyncDiscovery` pattern with service type `_sonora-jam._tcp`)
- Test: `shared/src/androidHostTest/.../jam/JamServerTest.kt` (loopback sockets, like `DeviceSyncSessionTest`)

**Interfaces:**
- Consumes: `JpakeHandshake`, `PairingCode`, `SecureChannel`, `JamFrameCodec`, `JamHostState.apply`.
- Produces: `class JamServer { suspend fun start(code: String): Int /*port*/; val events: Flow<JamEvent>; fun broadcast(frame: JamFrame); fun kick(guestId: String); fun stop() }`; `class JamClient { suspend fun join(host: String, port: Int, code: String, name: String): JamConnection }` with `val frames: Flow<JamFrame>` and `fun send(frame: JamFrame)`.
- Lockout: 3 wrong codes then the timed lock, copied from `DeviceSyncServer`; a per-session reconnect key valid for 30 s.

- [ ] **Step 1: Write failing tests**: correct code joins; wrong code three times locks out; eighth guest gets a `full` error; broadcast reaches every guest; one guest's disconnect does not stop others; a reconnect within 30 s with the key is accepted, after 30 s refused; `stop()` sends `JamEnd` to all.
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests '*JamServerTest*'`; expect FAIL. **Step 3: Implement** one `SecureChannel` per guest, each with its own J-PAKE round; **Step 4: Run**; expect PASS. **Step 5: Commit** `feat(jam): encrypted multi-guest server and client`.

### Task 11: Playback integration

**Files:**
- Create: `androidApp/.../jam/JamController.kt`, `JamService.kt` (foreground service, pattern of `DeviceSyncService.kt`)
- Modify: `playback/api/PlaybackConnection.kt` and `PlaybackConnectionImpl.kt`/`SonoraPlaybackService.kt` only as needed to read the queue state and to load a queue without recording stats for skipped-missing songs; `AndroidManifest.xml` (service declaration, `foregroundServiceType="mediaPlayback"` reuse or `dataSync` as sync does)
- Test: `androidApp/src/test/.../JamControllerTest.kt`

**Interfaces:**
- Consumes: Tasks 8–10, `PlaybackConnection`.
- Produces: `JamController { fun host(settings: JamSettings): HostSession /*code, port, guests*/; suspend fun join(...); fun leave(); val state: StateFlow<JamUiState> }`.

- [ ] **Step 1: Write failing tests** with fakes: host queue change broadcasts a `JamStateFrame`; guest mirrors the host queue and saves its previous queue; leaving restores the previous queue; a killed app restores the previous queue, not the Jam queue (Jam queue is never persisted); a host-driven skip is not recorded as a skip on the guest, the guest's own skip is; a song the guest lacks is skipped without any stats call; speed and pitch are never set (verify no `setCustomSpeed`/`setCustomPitch` calls).
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*JamController*'`; expect FAIL. **Step 3: Implement**; the guest starts on `ClockSync.toLocal`, seeks once only at the next pause or song change when `planPlayback` says so. **Step 4: Run**; expect PASS. **Step 5: Commit** `feat(jam): host and guest playback control`.

### Task 12: Jam UI, failure states and polish

**Files:**
- Create: `androidApp/.../ui/screens/jam/JamStartSheet.kt`, `JamHostScreen.kt`, `JamJoinScreen.kt`, `JamViewModel.kt`; Modify `NavKeys.kt`, `SonoraAppShell.kt`, Now Playing overflow menu, Weekly mix screen ("Start a Jam"), Settings (speaker delay −500..+500 ms, stored per device), strings in English and Japanese
- Test: `androidApp/src/test/.../JamViewModelTest.kt`

- [ ] **Step 1: Write failing tests**: join screen shows discovered Jams and falls back to manual `host:port`; wrong-code error and lockout countdown shown; "Jam is full" shown; host dropping shows "Jam ended" only after the current song ends; speaker delay shifts the guest start time by the configured ms; guests see add-song and (when allowed) skip/pause; seek bar and reorder are hidden for guests; the host can end the Jam and remove a guest.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*JamViewModel*'`; expect FAIL. **Step 3: Implement** the screens in the "Sleeve" style (`docs/DESIGN.md`), the host's code in large type, and a guest list. **Step 4: Run** `./gradlew :shared:testAndroidHostTest testDebugUnitTest lintDebug`; expect PASS. Update README, `docs/FEATURE_IDEAS.md`, and the Japanese strings. **Step 5: Commit** `feat(jam): jam screens, permissions and failure handling`.

---

## Self-review notes

- Spec coverage: smart shuffle (Tasks 1–2), weekly mix incl. countdown, save copy, edge cases (3–6), Jam join/security/protocol/matching/clock/permissions/queue/failure/stats/latency (7–12), no-QR and quality-over-sync constraints (Global Constraints, Tasks 9, 11).
- Not covered on purpose: streaming to guests, fetching missing songs, QR joining (spec non-goals).
