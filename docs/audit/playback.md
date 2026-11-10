# Sonora audit: playback, background & native Android

Scope: `lib/services/audio_handler.dart`, `android_auto_media_browser.dart`, `position_ticker.dart`,
`sleep_timer_notification_service.dart`, `volume_service.dart`, `home_widget_sync.dart`, `native_bridge.dart`,
`lib/providers/player_provider.dart` + parts (`player_queue`, `player_mfx`, `player_sleep_timer`, `player_theme`,
`player_persistence`, `player_library` where relevant), `lib/models/mfx_preset.dart`, `replay_gain.dart`,
widgets `custom_equalizer_widget`, `mfx_bottom_sheet`, `sleep_timer_sheet`, `audio_visualizer`, `speed_slider`,
native `MainActivity.kt`, `NowPlayingWidget.kt`, `AndroidManifest.xml`, widget resources, and the listed tests.

Current stack (to be replaced): `just_audio 0.10.6` (already Media3 ExoPlayer underneath on Android),
`audio_service 0.18.19` (MediaSessionCompat + MediaBrowserServiceCompat, foreground service),
`audio_session 0.2.x` (audio focus / attributes / becoming-noisy), `flutter_local_notifications 23`
(sleep timer notification). App id `de.yurtemre.sonora` (debug suffix `.dev`, versionName suffix `-dev`),
minSdk 24, targetSdk/compileSdk 37.

Layering today: `PlayerProvider` (UI state; logical `queue` of `Song`, `_originalQueue`, `currentIndex`,
shuffle/repeat, sleep timer, MFX, persistence) → `SonoraAudioHandler` (media session + sliding native window
+ fades + ReplayGain + EQ) → `just_audio AudioPlayer` (ExoPlayer). In Kotlin, collapse handler+provider into a
`MediaLibraryService` + a player wrapper (ForwardingPlayer) + a repository/ViewModel.

---

## 1. Media item identity

- MediaItem id = `Uri.file(song.filePath).toString()` → `file:///storage/emulated/0/Music/x.mp3`
  (Dart `Uri.file` percent-encodes spaces etc.). The id is also the playback URI.
- Fields: `title = song.displayTitle` (display title depends on title-filter settings, other audit),
  `artist`, `album`, `duration`, `artUri = file://<artworkPath>` (cached JPEG, see §13.2 scanMediaStore),
  `extras` = ReplayGain map (§6) or null.
- Provider maps media item → queue index by `queue.indexWhere(file uri == item.id)` (first match!
  duplicates of the same file in a queue resolve to the first occurrence — quirk).

---

## 2. Queue model

### 2.1 State
- `PlayerProvider.queue: List<Song>` — the order actually played (shuffled or not).
- `PlayerProvider._originalQueue: List<Song>` — unshuffled order, used to un-shuffle.
- `currentIndex: Int` (-1 = nothing), `isShuffled: Boolean`, `repeatMode: off|all|one`.
- `_playlistContext: String?` playlist id passed to stats when started from a playlist.
- Handler `_rawPlaylist: List<MediaItem>` mirrors `queue` 1:1 (same order).

### 2.2 Sliding native window (handler)
Purpose: only part of a huge logical queue (10k+) is loaded into the native player.
Constants:
- `_prependBuffer = 10`, `_appendBuffer = 30`.
- Initial load around `initialIndex` (clamped to [0, len-1]):
  `windowStart = max(0, initialIndex - 10)`, `windowEnd = min(len, initialIndex + 30)` (end exclusive).
  Native index = global − windowStart.
- On every current-index change (not during an edit): `remainingAfter = nativeLen - 1 - localIndex`,
  `remainingBefore = localIndex`.
  - Slide forward if `remainingAfter < 10 && windowEnd < len`: append `min(20, len - windowEnd)` items.
  - Slide backward if `remainingBefore < 5 && windowStart > 0`: prepend `min(15, windowStart)` items at native 0.
  - The window is **never trimmed** (only grows).
- After each edit `queue` (session queue) is re-broadcast as `rawPlaylist[windowStart, windowEnd)` — i.e. the
  media session queue exposed to notification/Auto/BT is only the window, not the whole queue.
- `_isModifyingSources` guard: index events during an edit are ignored; after the edit the current index is
  re-read and the media item / ReplayGain updated without sliding (test `gapless_transition_test`: a track
  change that lands during an append/remove must still update the current item and apply the new gain).
- Errors `PlayerInterruptedException`/`PlayerException` during slide → `_recoverPlayer()`: reload playlist
  around the current item (or index 0) and `play()`.

Porting advice: Media3 ExoPlayer handles thousands of MediaItems fine; a window may be unnecessary, but
MediaSession pushes the whole timeline to controllers (Auto/SystemUI) over binder — test 10k-item queues. If
you drop windowing, the observable behaviours below still must hold (gapless, current item, gains).

### 2.3 Load & play (`playSong(song, list, playlistId?)`, `_loadAndPlay`)
1. `++_playRequestToken`; reset listen-stats session.
2. `queue = list.copy()`, `_originalQueue = originalQueue.copy()`, `isShuffled = shuffled`.
3. `currentIndex = queue.indexWhere(id == song.id)`, or 0 if not found. Notify UI.
4. `loadPlaylist(items, initialIndex=currentIndex)` (position 0) → if token changed, abort.
5. `play()` (with fade-in, §4) → if token changed, abort.
6. Image preflight for upcoming queue (other audit).
Rapid taps: only the latest request proceeds; "Loading interrupted" errors swallowed.

`loadPlaylist([])` → `player.stop()`, clear sources, session queue `[]`, mediaItem `null`.

### 2.4 Shuffle
- `toggleShuffle()` (light haptic; no-op if no current song):
  - **Turn on**: `remaining = queue[currentIndex+1 ..]` shuffled with Dart `List.shuffle()` (Fisher–Yates,
    unseeded `Random`). New queue = `queue[0..currentIndex]` (unchanged, incl. history) + shuffled remaining.
    Current song keeps its index; playback is not interrupted. Native: `updatePlaylistAfter(currentIndex,
    remaining)` — removes every native item after current one by one, appends first `min(30, remaining)`,
    `windowEnd = windowStart + local + 1 + chunk`. On `PlayerInterruptedException` falls back to full reload.
  - **Turn off**: `queue = _originalQueue.copy()`, `currentIndex = indexOf(current.id in original)` (if
    found; else queue unchanged), `isShuffled=false`; full `loadPlaylist(queue, currentIndex,
    initialPosition = playbackState.updatePosition)` then `play()` if it was playing. Note: uses the last
    *broadcast* position (can be slightly stale → small jump back / audible reload glitch).
- `quickShuffle(list, playlistId?)`: shuffle whole list, start at `shuffled.first`, `_originalQueue = list`
  (unshuffled), `isShuffled = true`; records a "shuffle session start" stat.
- Native shuffle mode is never used (`setShuffleMode` is a no-op). Do NOT use ExoPlayer shuffle order unless
  you replicate "history stays, only upcoming shuffled, unshuffle restores original order at current song".
- Shuffle state persisted only via `last_played_is_shuffled` (§11).

How `_originalQueue` follows edits:
- add to queue → appended to both lists.
- play next → inserted after the current song in the original list (`indexWhere(id == current.id)+1`), or
  appended if current not found.
- remove → removes **all** entries with that song id from original.
- reorder → original NOT changed (reorders are lost when un-shuffling).

### 2.5 Repeat
- `cycleRepeatMode()`: off → all → one → off (light haptic). Maps to just_audio LoopMode off/all/one
  (AudioService `group` maps to all).
- **Not persisted**: app always starts with repeat off.
- just_audio semantics to preserve (they differ from ExoPlayer defaults!):
  - Repeat ONE: `hasNext/hasPrevious` return the current index, so **Next and Previous restart the same
    song** (`seekToNext` = seek(0) on the current item). ExoPlayer's `seekToNext` in REPEAT_MODE_ONE moves to
    the next item — override in a ForwardingPlayer if 1:1 is wanted.
  - Repeat ALL: wraps within the *native window*. If playback started in the middle of a large queue
    (`windowStart > 0`), wrapping goes to `windowStart`, not raw index 0 (latent bug; with a full playlist in
    ExoPlayer, REPEAT_MODE_ALL simply wraps to 0 — acceptable/better).
  - Off: at end of queue processing state = completed (player `playing` flag stays true in just_audio).
- Completed-state play/pause: `playPause()` → if completed and queue non-empty: `skipToQueueItem(0)` + `play()`.

### 2.6 Play next / add to queue
- `playNext(song)` / `playNextSongs(songs)`: insert at `currentIndex + 1` (or append if at end) in `queue`;
  mirror into original (above); handler `insertQueueItemsAt(global)`: if `windowStart <= global <= windowEnd`
  insert natively at `global - windowStart`, `windowEnd += n`; if before window shift both bounds; if after
  window, only logical. Multiple "play next" calls each insert directly after current → later calls end up
  *before* earlier ones (LIFO stacking), matching code.
- `addToQueue(song)` / `addSongsToQueue(songs)`: append to `queue` and `_originalQueue`; native append only if
  the window already reaches the end of the raw playlist.
- No duplicate check; the same song can appear multiple times.

### 2.7 Remove / reorder
- `removeFromQueue(index)`: removes from `queue`, all same-id entries from original, handler
  `removeQueueItemAt(index)` (native remove if inside window, else shift window bounds if before).
  `currentIndex`: decremented if `index < currentIndex`; if the removed item *is* current, index kept
  (clamped to last / -1 when empty). The native player itself advances to the following item when the
  current item is removed (ExoPlayer does the same), so the next song starts playing.
- `reorderQueue(old, new)` (both must be valid indices): move in `queue`; `currentIndex` follows the
  current song (`old==cur → new`; `old<cur<=new → cur-1`; `old>cur>=new → cur+1`); UI notified *before*
  the native move. Handler `moveQueueItem`: native move if both in window; else reload the window around the
  current song — **this reload restarts the current track at 0** (latent bug; ExoPlayer `moveMediaItem` avoids
  it).
- Queue UI (not this audit, but test `queue_windowing_test`): queue screen shows the list starting one item
  before the current (`displayOffset = currentIndex > 0 ? currentIndex - 1 : 0`); number column width
  `clamp(digits*9 + 16, 32, 52)`; tapping a non-current row calls `skipToQueueItem(actualIndex)`; swipe
  (end→start) removes by song id with a 2 s snackbar.

### 2.8 Skip / seek / previous
- `next()` (UI): if `currentIndex < queue.length-1` record a skip stat; then handler `skipToNext()`:
  native `hasNext` → `seekToNext`; else if more items beyond window → reload at next global index + play;
  else `seek(0)` (stay on last track).
- `previous()` (UI): if `position > 3 s` record a "restart" stat. Handler `skipToPrevious()`:
  **restart threshold = strictly greater than 3 seconds** → `seek(0)`; else previous native item, else
  reload window at previous global index + play, else `seek(0)`.
- `seek(pos)` (UI): if `pos < 3 s` and current position `> 3 s` record a restart stat.
- `skipToQueueItem(i)`: inside window → `seek(0, index)` (keeps playing/paused state as is); outside → reload
  + `play()`.
- Notification/BT/Auto/widget previous & next call the same handler methods (no stats recorded there).
- No fade on skip/seek; only play/pause transport fades (§4).

### 2.9 Gapless
- Achieved by ExoPlayer concatenated playlist (just_audio `setAudioSources` / `addAudioSources`). No
  crossfade. Every transition: `currentIndexStream` → new MediaItem broadcast; ReplayGain multiplier applied
  **before** broadcasting the item so the level changes as early as possible (§6). MFX (speed/pitch/EQ)
  re-applied by the provider on each new song id.
- `maxSkipsOnError = 0` (just_audio default): a decode error pauses playback (no auto-skip).

---

## 3. Audio focus, ducking, noisy, reconnect, background

### 3.1 Session configuration
- `AudioSessionConfiguration.music()` = usage MEDIA, contentType MUSIC, focus gain `AUDIOFOCUS_GAIN`,
  `androidWillPauseWhenDucked = pause_on_duck` setting (default false). Changing the setting reconfigures the
  session immediately.
- Focus requested when playback starts (just_audio activates the session on `play()`).
- just_audio interruption handling (default `handleInterruptions = true`):
  - Transient loss (`pause` type) → `player.pause()` **immediately** (bypasses Sonora's fade), remembers
    `_playInterrupted`; on focus regain → just_audio calls `player.play()` directly (no Sonora fade-in).
  - Permanent loss (`unknown`) → pause, no auto-resume.
  - Duck (`LOSS_TRANSIENT_CAN_DUCK`) with willPauseWhenDucked=false → nothing in app (system auto-ducks for
    usage MEDIA on Android 8+). With `pause_on_duck=true` the duck is reported as a pause interruption →
    pause and resume after.
  - Media3 equivalent: `setAudioAttributes(attrs, handleAudioFocus = true)`; for "pause on duck" Media3
    always ducks — you need custom focus handling (or `AudioFocusRequest.setWillPauseWhenDucked(true)` with
    your own listener) to pause on duck. Settings key `pause_on_duck`.

### 3.2 Becoming noisy (headphones unplugged / BT disconnected)
- Sonora listener: if `pause_on_disconnect` (default **true**) and playing → `_wasPlayingBeforeDisconnect =
  true`, `pause(immediate = true)` (no fade).
- **Discrepancy**: just_audio *also* unconditionally pauses on becoming-noisy (its own subscription), so in
  practice playback always pauses even when `pause_on_disconnect=false`; and if just_audio's listener runs
  first, `player.playing` is already false so `_wasPlayingBeforeDisconnect` may never be set (breaking
  resume-on-connect). Port the *intended* behaviour: honour the setting
  (`setHandleAudioBecomingNoisy(pauseOnDisconnect)` or own ACTION_AUDIO_BECOMING_NOISY receiver).
### 3.3 Resume on reconnect
- `resume_on_connect` (default **false**). On any audio-device list change: if enabled, the flag
  `_wasPlayingBeforeDisconnect` is set and not playing, and the device list contains any device whose type is
  not built-in speaker and not built-in earpiece → clear flag, `play()` (with fade-in).
- Kotlin: `AudioManager.registerAudioDeviceCallback` (`onAudioDevicesAdded`, outputs), same predicate
  (`TYPE_BUILTIN_SPEAKER`, `TYPE_BUILTIN_EARPIECE` excluded).

### 3.4 Foreground service / background
- Manifest: `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`;
  service `com.ryanheise.audioservice.AudioService` `foregroundServiceType="mediaPlayback"` exported with
  `android.media.browse.MediaBrowserService` intent filter. Replace with your `MediaLibraryService`
  (`androidx.media3.session.MediaLibraryService` + `MediaSessionService` filters).
- `androidStopForegroundOnPause = false` → service **stays in foreground while paused** (notification not
  dismissible while paused). Media3 default stops foreground on pause after a delay; to match, override
  `MediaSessionService` behaviour (e.g. `setShowNotificationForIdlePlayer` / keep foreground) — decide.
- No battery-optimisation request, no special Android 14+ code beyond the FGS type permission. POST_NOTIFICATIONS
  requested at permission flow (permission_service, other audit).
- Startup (`main.dart`): `AudioService.init` → `SleepTimerNotificationService.initialize()` →
  `VolumeService.ensureMediaVolume()` (if STREAM_MUSIC volume is 0, set it to `max/3` (min 1), flags 0 = no UI;
  returns whether raised).

### 3.5 Task removed ("Keep playing on app close")
- `keep_playing_on_close` default **false**. `onTaskRemoved`: if false → `player.stop()` (+ base no-op);
  queue/last-played prefs are NOT cleared, so the session is restorable next launch. If true → nothing,
  playback continues in the service.
- Media3: override `MediaSessionService.onTaskRemoved` → if !keepPlaying: `player.stop()`/`pauseAllPlayersAndStopSelf()`.

### 3.6 Clean close / stop
Triggers: notification "Close player" custom action (`closePlayer`), notification swipe-dismiss
(`onNotificationDeleted`), provider `stop()`.
Handler `stop()`:
1. Cancel transport fade (token++), `wantsPlayback=false`; `player.stop()`.
2. Restore pre-fade volume if a fade was active.
3. Clear raw playlist & window, session queue `[]`, mediaItem `null`, playbackState idle/not playing with no
   controls/system actions/compact indices.
4. MethodChannel `stopForegroundService` → reflection on AudioService.instance: `stopForeground(REMOVE)`;
   `NotificationManager.cancel(1124)` (audio_service notification id).
5. Provider (`closePlayer` callback or `stop()`): `queue=[]`, `_originalQueue=[]`, `currentIndex=-1`,
   `_playlistContext=null`, reset listen session, **clear last-played prefs** (no restore next launch), notify.
6. Home widget receives `null` → "Nothing playing" (§10).

---

## 4. Fades

### 4.1 Transport fade (play / pause)
- Duration `400 ms`, `20` steps → step every `20 ms`. Linear: each step
  `next = current + (target - current) / remainingSteps` (remainingSteps = 20 - step + 1), i.e. a linear ramp
  from whatever the volume is now.
- `play()`:
  - token++ ; `wantsPlayback = true`. If already playing and no fade active → return.
  - If not playing: `restore ??= (volume > 0.05 ? volume : 1.0)`; set volume 0; start player (not awaited);
    then ramp up to `restore` (re-read every step so `setVolume` during the fade retargets).
  - When done (token unchanged) clear `restore`.
- `pause(immediate=false)`:
  - token++ ; `wantsPlayback=false`. If not playing: just restore pending volume and return.
  - `restore ??= (volume > 0.05 ? volume : 1.0)`; ramp to 0 over 400 ms (skipped if `immediate`);
    `player.pause()`; then set volume back to `restore` (so player volume = user volume while paused).
  - If a play() arrives mid-fade (token changed) → abort the fade-out, and after pause/restore resume if
    playback is wanted (reversal handling).
- Quirk: a user volume ≤ 0.05 is treated as "faded" and restored to **1.0** after a fade.
- Immediate pauses (no fade): becoming-noisy, sleep-timer finish-song boundary, and just_audio's own focus
  interruptions. Stop, skip, seek: no fade.
- Media button / notification / widget play-pause go through handler `play()/pause()` → faded.
- Kotlin: implement in a ForwardingPlayer overriding `play()/pause()` with a coroutine ramp on
  `player.volume` (volume = userVolume × ReplayGain, §6).

### 4.2 Sleep-timer fades — see §8.

---

## 5. Speed, pitch, MFX, equalizer

### 5.1 Speed (custom)
- `setSpeed(v)`: clamp `[0.5, 2.0]`; persisted `speed` (double, default 1.0). Applied with pitch 1.0
  (time-stretch) unless an MFX preset is active (then stored but not applied).
- UI `SpeedSlider`: discrete steps `[0.5, 1.0, 1.25, 1.5, 2.0]` (4 divisions; closest step shown);
  reset button → 1.0; label `"{speed}x"` with trailing zeros trimmed. Disabled (opacity 0.5) while a preset
  is active.

### 5.2 MFX presets (`MfxPreset`, pitch == speed, i.e. resample "nightcore" style)
| enum name | speed=pitch | title |
|---|---|---|
| superSlowed | 0.70 | "Super Slowed" |
| slowed075 | 0.75 | "25% slower" |
| slowed080 | 0.80 | "20% slower" |
| slowed | 0.85 | "Slowed" |
| slowed090 | 0.90 | "10% slower" |
| slowed095 | 0.95 | "5% slower" |
| normal | 1.00 | "Normal" |
| spedUp105 | 1.05 | "5% faster" |
| spedUp110 | 1.10 | "10% faster" |
| spedUp115 | 1.15 | "15% faster" |
| spedUp120 | 1.20 | "20% faster" |
| spedUp | 1.25 | "Sped Up" |
| nightcore | 1.30 | "Nightcore" |
| superSpedUp | 1.50 | "Super Sped Up" |
Subtitle `"{speed}x Speed"` (`speed.toStringAsFixed(2)`, normal → "1.0").
Icons: slower → fast_rewind, normal → play_arrow, faster → fast_forward, nightcore → bolt.
- `setMfxPreset(p)`: null/normal → no preset. Applies `setSpeed(p.speed)` + `setPitch(p.pitch)`
  (Media3 `PlaybackParameters(speed, pitch)`); without preset: `setSpeed(customSpeed)`, `setPitch(1.0)`.
- `fromSpeed(s)` matches with tolerance 0.001; `fromName(name)` by enum name.
- `effectiveSpeed` = preset speed or custom speed; `effectiveDuration = round(durationMs / speed)` shown in
  MFX sheet as `"m:ss (orig.) → m:ss (1.3x)"`.
- MFX re-applied on every new track (`_applyMfx` when the current song id changes) and at settings load.

### 5.3 Equalizer (Android `android.media.audiofx.Equalizer`)
- Created by just_audio as `new Equalizer(priority 0, audioSessionId)` on the player's session. No
  LoudnessEnhancer, BassBoost, Virtualizer or PresetReverb is used ("Reverb/Warmth" is EQ only).
- Parameters: bands = `getNumberOfBands()` (device dependent, commonly 5: 60, 230, 910, 3600, 14000 Hz),
  `centerFrequency = getCenterFreq(i)/1000.0` Hz, `minDecibels = getBandLevelRange()[0]/100.0`,
  `maxDecibels = getBandLevelRange()[1]/100.0` (commonly −15/+15 dB). `setGain(dB)` →
  `setBandLevel(i, round(dB*100))` millibels.
- Enabled iff eqMode != "off". When disabled, band gains are left as they are (just disabled).
- Mode precedence: `lofi` > `warmth` (setting name "Reverb") > `bass_boost` > `custom` > off.
  Lo-Fi, Warmth, Bass Boost are mutually exclusive toggles (enabling one turns the other two off; does not
  turn custom EQ off, but takes precedence). Setting custom bands turns Lo-Fi/Warmth/Bass off and custom on.
- Exact gains per band (by `centerFrequency f` in Hz; `min`/`max` = device range):

| Mode | Standard | Bluetooth patch |
|---|---|---|
| lofi | f > 2000 → `min` (full cut); f < 150 → `min*0.3`; else 0 | f > 3000 → `min*0.55`; f < 150 → `min*0.20`; 500 ≤ f ≤ 2000 → `max*0.15`; else 0 |
| warmth | f < 250 → `max*0.5`; f > 4000 → `min*0.5`; else 0 | f < 250 → `max*0.30`; f > 4000 → `min*0.25`; else 0 |
| bass_boost | f < 250 → `max*0.8`; else 0 | f < 250 → `max*0.40`; else 0 |
| custom | `customGains[bandIndex].clamp(min,max)` for bands with index < gains.length | same (patch ignored) |
(Test `equalizer_and_mfx_test` pins the ±15 dB examples: bass 12 vs 6 dB, warmth +7.5/−7.5 vs +4.5/−3.75,
lofi −15 vs −8.25.)
- The Bluetooth patch is a manual toggle (`mfx_bluetoothMfxPatch`), not auto-detected.
- Errors (EQ unsupported) are swallowed.
- Custom EQ UI (`CustomEqualizerWidget`, Android only, inside MFX sheet): Switch "Custom Equalizer" (on →
  `setCustomEqBands(current gains)`; **off → `resetAllMfx()`** — resets everything incl. speed/preset).
  Preset chips (only when on), 5-value fractions mapped to N bands by
  `mapped = floor(i / N * 5).clamp(0,4)`; gain = `f >= 0 ? f*max : -f*min`:
  - Flat `[0,0,0,0,0]`, Pop `[-0.2,0.2,0.5,0.2,-0.2]`, Rock `[0.5,0.3,-0.3,0.3,0.5]`,
    Jazz `[0.4,0.2,-0.2,0.2,0.4]`, Classical `[0.5,0.3,-0.2,0.4,0.4]`.
  Vertical sliders per band (min..max dB, continuous), applied on drag end; label `"{f/1000}k"` if ≥1000 Hz
  else `"{f}"`. Disabled (opacity 0.5) when off. Gains list initialised to zeros if length mismatch.
- `resetAllMfx()`: preset none, all toggles off, custom EQ off (gains kept), speed 1.0 (persisted).
- MFX sheet (bottom sheet, max 60% height, "Experimental" badge): duration info, preset carousel (cards
  140 wide, margin 12, auto-scroll to centre selected, 300 ms easeOutCubic), switches Warmth/Lo-Fi/Bass
  Boosted/Bluetooth patch, custom speed slider, custom EQ, "Reset All Effects".
- Persisted keys (§11): `mfx_preset_name` (+ legacy `mfx_preset` double), `mfx_isSlowed`, `mfx_isSuperSlowed`,
  `mfx_isSpedUp`, `mfx_isSuperSpedUp`, `mfx_isNightcore`, `mfx_isReverbEnabled`, `mfx_isLofiEnabled`,
  `mfx_isBassBoostEnabled`, `mfx_isCustomEqEnabled`, `mfx_bluetoothMfxPatch`, `mfx_customEqGains`
  (string list of doubles). Load order: preset by name → by legacy speed → by legacy booleans
  (superSlowed > slowed > superSpedUp > spedUp > nightcore).
- Media3 note: the Equalizer must be attached to ExoPlayer's `audioSessionId` (generate one via
  `Util.generateAudioSessionIdV21` / `C.AUDIO_SESSION_ID_UNSET` listener) and recreated if the session id
  changes. Re-apply bands after re-creation.

---

## 6. ReplayGain & volume

- Tags are read in Dart (`replay_gain_reader.dart`, library audit) and carried in MediaItem extras:
  `rg_track_gain`, `rg_album_gain` (dB), `rg_track_peak`, `rg_album_peak` (linear). Extras null if neither
  gain present.
- Setting `replay_gain_mode` = `off` (default) | `track` | `album`.
- Multiplier:
  ```
  if info == null || mode == off: 1.0
  gain = album ? (albumGain ?: trackGain) : (trackGain ?: albumGain)
  if gain == null || !finite: 1.0
  peak = trackPeak ?: albumPeak
  m = 10^(gain/20)
  if peak != null && peak > 0 && m*peak > 1.0: m = 1/peak
  ```
  No pre-amp; positive gains allowed but…
- Player volume = `clamp(userOrFadeVolume * m, 0, 1)` (player cannot amplify → positive gains are capped).
  All volume writes go through this single function.
- Applied when the media item changes (before broadcast), when mode changes, and on library resync when the
  current item's tags changed (`syncQueueMetadata`).
- User volume (`PlayerProvider.setVolume`): clamp 0..1, rounded to 2 decimals, persisted `volume` (default 1.0;
  0.1 on Windows only). It is the in-app player gain (not STREAM_MUSIC). Restored at settings load via
  `applyVolume`.
- `VolumeService.ensureMediaVolume` — see §3.4.

---

## 7. Position ticker & visualizer

### 7.1 PositionTicker
- One shared position stream; period **200 ms** while playing; no timer while paused (emits on player events
  only).
- Runs only when app is foreground (lifecycle not hidden/paused/detached) **and** ≥1 listener. On resume emits
  the current position immediately. New listener gets current position immediately.
- Monotonic filter: a backward move smaller than **1500 ms** is dropped unless caused by a discontinuity (seek /
  track change / repeat loop). Fixes ExoPlayer's start-of-song position correction jitter. Identical values not
  re-emitted. Reset of "last emitted" when inactive.
- Kotlin: a `Flow` polling `player.currentPosition` every 200 ms while `isPlaying` & UI started, with the same
  1500 ms backward-drop rule (reset on `onPositionDiscontinuity` / media item transition).

### 7.2 Listen statistics timers (provider; stats audit for storage)
- Recent-play threshold = clamp(25% of duration, 10 s, 30 s); complete listen = 80% of duration.
- One-shot timer armed for the earliest pending milestone, delay `(milestone - position)/speed`; re-armed on
  play/pause, discontinuity, speed change, new track. Tolerance +500 ms at fire. Auto-advance/repeat-one loop
  starts a fresh playthrough; seeking back to < 3 s also restarts the session.

### 7.3 Visualizer — fake animation, no FFT/audio data
- `AudioVisualizer(isPlaying, color, barCount=12, height=32)`; Now Playing uses `barCount=20, height=80`,
  color = primary @ 0.8 alpha, only if setting `show_visualizer` (default false); clipped bottom radius 32.
- Wave controller repeats every 1500 ms (t ∈ [0,1)); amplitude controller 450 ms (forward on play, reverse on
  pause; wave stops when amplitude reaches 0).
- Bar i: `offset = i*2π/barCount`; `s1 = sin(2πt + offset)`; `s2 = cos(2π·2t − 1.4·offset)`;
  `norm = (s1+s2+2)/4`; `h = 4 + norm*(H−4)*amp` (amp 0 → 4 px). Spacing 4 px, width
  `(W − 4*(n−1))/n`, bottom-aligned, corner radius width/2.

---

## 8. Sleep timer

### 8.1 Settings
- `sleep_timer_default_minutes` default 5 (settings dropdown: 5,10,15,20,25,30,45,60).
- `sleep_timer_fade_out_seconds` default 10 (dropdown 0=Off,10,30,60).
- `sleep_timer_finish_song` default false.

### 8.2 Sheet UI
- Inactive: big "{n} min" label, slider 1–60 min (59 divisions), initial = default clamped 1..60; preset
  chips `[5,10,15,20,30,45,60]` (default one outlined/bold); switch "Finish current song before sleeping"
  (initial from setting); button "Start timer ({n} min)" → `startSleepTimer(n min, finishSong)`; closes.
- Active: "Music stops in" + `h:mm:ss` / `m:ss`, or when finishing "Finish current song…" + "Finishing song...";
  buttons "Cancel Timer" (stop + close) and "+1 min" (extend 1 min).

### 8.3 Logic (`startSleepTimer(duration, finishSong?, fadeOutSecs?)`)
- Stops any previous timer; `remaining = duration`; `_originalVolumeBeforeFade = userVolume`;
  finishSong defaults to setting, fade defaults to setting.
- Updates media notification custom actions (§9) and shows the timer notification.
- 1 s periodic tick: `remaining -= 1 s`.
  - If remaining ≤ 0: set 0, cancel tick; if finishSong && playing && currentSong → finish-song mode;
    else `_finalizeSleepTimerStop()`.
  - Else, if !finishSong && fade > 0 && `remaining.inSeconds <= fade`: on first entry capture current user
    volume; `applyVolume(orig * remainingSec / fadeSecs)` (linear, 1 s steps, bypasses transport fade).
    Update notification.
- Finish-song mode: restore volume to `orig`; notification body "Finishing song..."; 250 ms poll:
  - not playing (user paused) → finalize;
  - song id changed / null → `pause(immediate)` + finalize (so next track never plays);
  - `songRemaining = (player.duration ?: song.duration) − position`; if ≤ 250 ms or pos ≥ dur →
    `pause(immediate)` + finalize;
  - if fade > 0 and `songRemaining.inSeconds <= fade` → `applyVolume(orig * remainingMs/(fade*1000))`.
  (Note: playback speed isn't factored in.)
- `_finalizeSleepTimerStop()`: cancel timers; state cleared **before** awaits (a timer started during the pause
  fade must survive — test); cancel notification; `pause()` (normal 400 ms fade) ; `applyVolume(userVolume)`;
  update media controls. Queue is NOT cleared (test).
- `stopSleepTimer()`: cancel everything, if fading restore `_originalVolumeBeforeFade` (the user volume, not a
  mid-fade value — test), update controls, cancel notification.
- `extendSleepTimer(d)`: if no timer → start a new one of `d` (defaults from settings). Else cancel finishing
  mode, restore volume if fading, restart with `remaining + d`, same finishSong flag. All "+1 min" entry points
  use 1 minute.
- Countdown ticks update only a separate `ValueNotifier` (not the whole provider) — test.

### 8.4 Timer notification (flutter_local_notifications, Android only)
- Channel id `de.yurtemre.sonora.sleep_timer`, name "Sleep Timer", description "Ongoing notification for
  active sleep timer countdown", importance LOW, priority LOW. Notification id **8888**, small icon
  `@drawable/ic_launcher_monochrome`, ongoing, not auto-cancel, onlyAlertOnce, no timestamp.
- Title = l10n `sleepTimer` ("Sleep Timer"); body = `"{time} remaining"` with time `mm:ss` or `hh:mm:ss`
  (zero-padded), or `"Finishing song..."`. Re-posted every second.
- Actions: `add_1_min` → label "+1 min" → extend 1 min; `end_timer` → "End timer" → `stopSleepTimer()`.
  Both `showsUserInterface: true` (tapping opens the app). Kotlin: use PendingIntents to the service; decide
  whether to keep launching the activity. Consider a chronometer (`setUsesChronometer` + `setChronometerCountDown`)
  instead of per-second reposts — behaviour change, optional.

---

## 9. Media notification & session

Config (`AudioServiceConfig`): channel id `de.yurtemre.sonora.audio`, channel name "Sonora Music", small icon
`drawable/ic_launcher_monochrome`, `androidStopForegroundOnPause=false`, `androidResumeOnClick=false`,
notification click opens MainActivity (default), notification id 1124, not ongoing flag, no badge.

Playback state controls (rebuilt on every player event and sleep-timer change):
- Idle → no controls, no system actions.
- Otherwise controls in order:
  0. Skip previous
  1. Play or Pause (by state)
  2. Skip next
  3. Custom `closePlayer` — icon `drawable/ic_close` (white X vector), label "Close player" (hard-coded English)
  4. If sleep timer active: custom `extendSleepTimer` — icon `ic_launcher_monochrome`, label l10n "+1 min"
  5. If sleep timer active: custom `stopSleepTimer` — icon `ic_launcher_monochrome`, label l10n "End timer"
- Compact (collapsed) indices `[0,1,2]`. System actions: seek (seek bar), skipToPrevious, skipToNext.
- `queueIndex` = global index (windowStart + native index); session queue = window slice.
- Artwork: MediaItem `artUri` file:// → audio_service decodes the local file into the notification/metadata
  bitmap. In Media3 set `MediaMetadata.artworkUri` (file:// works for the in-process notification via
  `DataSourceBitmapLoader`, but NOT for Android Auto / other processes — expose via a `content://` provider or
  `artworkData`).
- Media3 mapping: `MediaSession.setCustomLayout`/`setMediaButtonPreferences` with `CommandButton`s for
  close / +1 min / end timer (custom `SessionCommand`s `closePlayer`, `extendSleepTimer`, `stopSleepTimer`).
  On Android 13+ the system media controls show at most 2 custom actions beside prev/play/next — order
  matters (close first today).
- Notification swipe (possible when not foreground) → same as Close (§3.6).
- Media buttons (BT/headset/widget): audio_service `click()` — PLAY_PAUSE / HEADSETHOOK → toggle (via faded
  play/pause), NEXT → skipToNext, PREVIOUS → skipToPrevious (3 s restart rule applies); PLAY, PAUSE, STOP,
  FAST_FORWARD/REWIND (10 s default intervals) map to the respective commands. No multi-click (double/triple
  tap) handling. Media3's default media-button handling (incl. double-tap HEADSETHOOK → next) differs slightly.
- `customAction` dispatch → provider: `extendSleepTimer` → +1 min; `stopSleepTimer` → stop; `closePlayer`
  → handler stop + provider clear.

---

## 10. Android Auto / Automotive browse tree

Manifest meta-data `com.google.android.gms.car.application` → `@xml/automotive_app_desc`
(`<automotiveApp><uses name="media"/></automotiveApp>`). Library data pushed into the browser every time the
library snapshot refreshes (songs, album groups, artist groups, playlists).

Tree (ids exact; `playable:false` → BROWSABLE flag; leaf songs playable):
```
root                                  (audio_service browsable root id "root"; "recent" root returns [])
├── root_songs      "Songs"           → every song in allSongs order (current library sort)
├── root_albums     "Albums"          → album:<albumName>|||<albumArtist>  title=album, subtitle(artist)=album artist
│     └── album:<name>|||<artist>     → album.songs
├── root_artists    "Artists"         → artist:<name>  title=name, subtitle="<N> songs"
│     └── artist:<name>               → artist.songs
└── root_playlists  "Playlists"       → playlist:<id>  title=name, subtitle="<N> songs" (N = songIds.length)
      └── playlist:<id>               → songs resolved from songIds (missing ids skipped)
```
- Root labels and "N songs" are hard-coded English.
- Art: album → first song with an existing artwork file; artist → local artist image if exists, else first song
  art; playlist → cover image if exists, else first song art. Song items: song art.
- Song item fields as §1 (no ReplayGain extras in browse items).
- `getItem(id)`: root categories, songs by file URI, album/artist/playlist by id.
- `search(query)`: case-insensitive substring on title/artist/album, max **20** songs.
- `playFromMediaId(id)`:
  - song file URI → queue = **entire library** (allSongs), start at that song;
  - `album:` → album songs from 0; `artist:` → artist songs from 0; `playlist:` → playlist songs from 0.
  - → provider `playSong(songs[start], songs)` (no playlist context recorded), else direct load+play.
- `playFromSearch(query)` priority: first artist whose lowercase name equals/contains query → artist songs;
  then album name → album songs; then first song title containing query → whole library from that song;
  else whole library randomly shuffled (start 0, isShuffled=false!). Quirk: empty query ("play music") matches
  the first artist (`"".contains`).
- Media3: `MediaLibraryService.MediaLibrarySession.Callback.onGetLibraryRoot/onGetChildren/onGetItem/
  onSearch/onGetSearchResult/onAddMediaItems/onSetMediaItems` (resolve id → list + start index). Large flat
  "Songs" list: use paging.

---

## 11. Persisted playback state & restore

Storage today: `SharedPreferencesAsync` → on Android a **Jetpack DataStore** file
`FlutterSharedPreferences` (`datastore/FlutterSharedPreferences.preferences_pb`), keys unprefixed; ints as
Long, doubles as Double, bools; string lists stored as string `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!"
+ JSON array` (older `LIST_PREFIX` base64 format possible). A native port can migrate by reading that DataStore.

Keys (this area):
| key | type | default | notes |
|---|---|---|---|
| last_played_song_id | int | — | current song id |
| last_played_queue_ids | string list | — | queue song ids (as strings) |
| last_played_original_queue_ids | string list | — | unshuffled ids |
| last_played_position_ms | int | 0 | position at save time |
| last_played_is_shuffled | bool | false | |
| restore_last_played_song | bool | true | setting |
| keep_playing_on_close | bool | false | |
| pause_on_duck | bool | false | |
| pause_on_disconnect | bool | true | |
| resume_on_connect | bool | false | |
| replay_gain_mode | string | "off" | off/track/album |
| volume | double | 1.0 | in-app gain |
| speed | double | 1.0 | |
| mfx_* | see §5.3 | | |
| sleep_timer_default_minutes / sleep_timer_fade_out_seconds / sleep_timer_finish_song | int/int/bool | 5/10/false | |
| show_visualizer | bool | false | |
Repeat mode is NOT persisted. Sleep timer is not persisted (dies with process).

Save triggers (`_saveLastPlayedState`, only if a current song exists): every media item change that maps to a
queue index, every playing-state change, app lifecycle paused/inactive/detached. No periodic position save.
Clear triggers: Close player / notification dismiss / provider stop.

Restore (`restoreLastPlayedState`), run once on the first library load (`updateSongs` when nothing is queued):
1. Abort if setting off, library empty, or something already queued, or no saved song id, or the song id is
   not in the library.
2. Rebuild queue from saved ids that still exist (fallback `[song]`), original queue likewise (fallback copy of
   queue). `currentIndex = indexOf(savedId)` or 0. `isShuffled = saved`.
3. `loadPlaylist(queue, currentIndex, initialPosition = saved ms)` — **paused, not auto-played**.
4. Theme colour extraction for the song.
Library resync while playing: queue entries swapped for rescanned songs with the same file path (keeps order),
handler raw playlist metadata patched by URI, ReplayGain of current item refreshed.

---

## 12. Home screen widget ("Now playing")

### 12.1 Dart → native sync
- Channel `de.yurtemre.sonora/home_widget`, method `update`, args map
  `{title: String, artist: String?, artPath: String?, isPlaying: Boolean}` or `null` (= nothing loaded).
- Source of truth: handler `mediaItem` + `playbackState.playing` streams (so notification, Auto, buttons all
  update it). `artPath` = file path from a `file:` artUri, else null.
- Deduplicated: only sent when (title, artist, artPath, isPlaying) changes (position ticks don't). On failure
  the previous value is restored so the next change retries. Initial `_last = null`, so a fresh app start with
  nothing loaded does not overwrite the persisted widget.
- Native registers the handler on the activity engine (`configureFlutterEngine`) and, on every widget
  broadcast, on audio_service's cached engine (`attachToRunningEngine`). In Kotlin just call
  `NowPlayingWidget.update(state)` from a `Player.Listener` in the service (onMediaMetadataChanged /
  onIsPlayingChanged), with the same dedupe.

### 12.2 Native state
- SharedPreferences file `sonora_home_widget`: `has_track` (bool), `title`, `artist`, `art_path` (strings),
  `playing` (bool). `null` update → has_track=false, others null.
- Rendering on a single-thread executor; receivers use `goAsync()` and finish after drawing.

### 12.3 Provider & layouts
- `NowPlayingWidgetProvider` (receiver, exported=false, label "Now playing"); info `now_playing_widget_info`:
  minWidth 250dp, minHeight 40dp, minResizeWidth 180dp, minResizeHeight 40dp, resize horizontal|vertical,
  targetCell 4×1, `updatePeriodMillis=0`, category home_screen, initial/preview layout = small,
  description "Shows the current song with playback controls".
- Layout choice per instance: `OPTION_APPWIDGET_MIN_HEIGHT >= 100dp` → large, else small. Re-rendered in
  `onUpdate` and `onAppWidgetOptionsChanged`.
- Small `widget_now_playing` (4×1): horizontal, padding `widget_padding` 8dp, background
  `widget_background` (rounded rect, `widget_surface`, radius 20dp / system background radius on 31+).
  Art 48dp wide (match height, max 48dp, fitCenter) → text column (margin 12dp start/4dp end; title 14sp
  sans-serif-medium `widget_on_surface`, artist 12sp `widget_on_surface_variant`, 1 line, ellipsize end) →
  buttons prev / play-pause / next (each 40×40dp, padding 8dp; play-pause has circular `widget_primary`
  background, tint `widget_on_primary`, margins 4dp; prev/next tint on_surface_variant, borderless ripple).
- Large `widget_now_playing_large` (4×2+): padding 12dp, art 96dp, column (margin 16dp) with text block (title
  16sp, artist 14sp, vertically centred, weight 1) above a centred button row (same buttons).
- Colours: light `surface #F3EDF7, on_surface #1D1B20, on_surface_variant #49454F, primary #6750A4,
  on_primary #FFFFFF, placeholder #E8DEF8, on_placeholder #6750A4`; dark `#211F26, #E6E0E9, #CAC4D0,
  #D0BCFF, #381E72, #4A4458, #D0BCFF`; API 31+ dynamic: light neutral1_50/neutral1_900/neutral2_700/
  accent1_600/accent1_0/accent2_100/accent2_700, dark neutral1_900/neutral1_50/neutral2_200/accent1_200/
  accent1_800/accent2_700/accent2_100. Dimens: art radius 12dp (31+: `system_app_widget_inner_radius`).
- Icons: vector 24dp play `M8,5v14l11,-7z`, pause `M6,19h4V5H6v14zM14,5v14h4V5h-4z`, next
  `M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z`, previous `M6,6h2v12H6zM9.5,12l8.5,6V6z`; placeholder = rounded
  `widget_placeholder` square with a 28dp music-note in `widget_on_placeholder`.

### 12.4 Content
- has_track: title (or ""), artist (or ""), art bitmap or placeholder drawable.
- no track: "Nothing playing" / "Tap to open Sonora", art = `@mipmap/ic_launcher`.
- Art loading: decode bounds; `inSampleSize` doubles while both `w/(2s) >= size && h/(2s) >= size`;
  centre-crop to square of `size = min(dimen(48dp or 96dp) px, 384)` with rounded corners (radius
  `widget_art_radius`) via BitmapShader; cached per size within one render.
- Play/pause icon shows pause only if `controlsPlayback && playing` (stale flag ignored if service dead);
  content description "Play"/"Pause".
- Strings (en / ja): widget_label "Now playing"/"再生中", description, nothing_playing "Nothing playing"/
  "再生中の曲はありません", tap_to_open "Tap to open Sonora"/"タップして Sonora を開く", album_art, play, pause,
  previous, next (ja: 再生, 一時停止, 前の曲, 次の曲).

### 12.5 Button actions
- Art and text → open app (`MainActivity`, ACTION_MAIN/LAUNCHER, NEW_TASK|RESET_TASK_IF_NEEDED,
  PendingIntent requestCode 0, UPDATE_CURRENT|IMMUTABLE).
- `controlsPlayback = has_track && playbackServiceReady` (reflection: AudioService.instance != null and
  listener != null). If true buttons are broadcasts to the provider (`de.yurtemre.sonora.widget.PLAY_PAUSE`
  rc 1, `.NEXT` rc 2, `.PREVIOUS` rc 3) which send `ACTION_MEDIA_BUTTON` with a single `ACTION_DOWN` KeyEvent
  (PLAY_PAUSE / NEXT / PREVIOUS) to audio_service's MediaButtonReceiver; else all buttons open the app.
  If the service died between draw and tap: open the app and re-render.
- Media3 port: use `MediaController`/`MediaButtonReceiver` or service PendingIntents
  (`MediaSession` `PendingIntent`s); "service ready" = your service's session is alive. Cold start: opening the
  app restores the last queue (§11) — keep that, or support playback resumption
  (`onPlaybackResumption`) as an upgrade.

---

## 13. MethodChannels (all must become plain Kotlin)

### 13.1 `de.yurtemre.sonora/volume`
| method | args | returns | native behaviour |
|---|---|---|---|
| ensureMediaVolume | – | Boolean | STREAM_MUSIC == 0 → set to `max/3` (≥1), flags 0; true if raised |
| getMediaVolume | – | `{current:Int,max:Int}` | STREAM_MUSIC values (unused in Dart) |
| getAndroidSdk | – | Int | `Build.VERSION.SDK_INT` (permission flow) |
| getDeviceAbi | – | String | `SUPPORTED_ABIS[0]` normalised: contains arm64/aarch64 → `arm64-v8a`; v7/arm → `armeabi-v7a`; x86_64; x86; else raw (update service picks APK) |
| installApk | filePath | true / error INVALID_PATH, FILE_NOT_FOUND, INSTALL_ERROR | FileProvider uri (`<pkg>.fileprovider`) → ACTION_VIEW `application/vnd.android.package-archive`, NEW_TASK|GRANT_READ (needs REQUEST_INSTALL_PACKAGES) |
| openUrl | url | Boolean | blank → false; ACTION_VIEW url NEW_TASK; exceptions → false (Dart also returns false for "") |
| getPackageInfo | – | `{appName:"Sonora", packageName, version: versionName ?: "1.0.0", buildNumber: longVersionCode string}` | error PACKAGE_INFO_ERROR. Dart fallback `Sonora / de.yurtemre.sonora / 1.20.0 / 1`; cached; `isDevBuild = debug || pkg endsWith ".dev" || version contains "-dev"` |
| shareFiles | filePaths: List<String>, text?, title? (default "Share") | Boolean | existing files only → FileProvider URIs; 1 → ACTION_SEND, ≥2 → ACTION_SEND_MULTIPLE, type `*/*`, EXTRA_TEXT if non-blank, GRANT_READ; wrapped in chooser(title). Used to share playlist (m3u) |
| stopForegroundService | – | Boolean | stop audio_service foreground + cancel notification 1124 (obsolete in Media3) |

### 13.2 `de.yurtemre.sonora/mediastore`
| method | args | returns | native behaviour |
|---|---|---|---|
| scanMediaStore | folderPath: String?, isFast: Boolean | `{songs: List<Map>, artist_images: Map<lowerName,path>, perf_query_cursor_ms, perf_cursor_loop_ms, perf_total_kotlin_ms}` | background thread; see below |
| scanFile | path | true | `MediaScannerConnection.scanFile` (no Dart caller found) |
| openFileFolder | filePath | Boolean (error INVALID_PATH) | 6-strategy folder opener, below |
| getDynamicWallpaperColor | – | Int ARGB or null | API 31+: `getColor(android.R.color.system_accent1_600)` |
| cropAndResizeImage | sourcePath, targetPath, targetSize=512, quality=85 | Boolean | decode, centre-crop square (min side), scale to targetSize only if larger (filtered), mkdirs, JPEG quality → playlist covers |

`scanMediaStore` details (library audit overlaps; documented for completeness):
- Query `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`, projection `_ID, TITLE, ARTIST, ALBUM, DURATION, DATA,
  ALBUM_ID, TRACK, YEAR, DATE_MODIFIED, SIZE` (+ `COMPOSER` on API 30+, unused), selection
  `IS_MUSIC != 0 AND (DATA LIKE ? ESCAPE '\' OR …)` with `<folder>/%` for the folder and its `/sdcard` ↔
  `/storage/emulated/0` variant (LIKE-escaped `\ % _`), then re-checked with case-insensitive `startsWith`.
- Per row map: `id, title (fallback filename w/o ext), artist ("Unknown Artist" if empty/"<unknown>"), album
  ("Unknown Album"), duration_ms, file_path, artwork_path, track_number (raw TRACK incl. disc*1000),
  year, last_modified_ms (DATE_MODIFIED*1000), file_size, album_id, has_lyrics` (sibling `.lrc` or `.txt`).
- Album art cache `cacheDir/album_art/art_album_<albumId>.jpg`; no-art marker `art_album_<id>.none`.
  Extraction API 29+: `loadThumbnail(albums uri, 720×720)` → JPEG q90 via `.tmp` + rename; older:
  `content://media/external/audio/albumart/<id>` stream copy. FileNotFound → marker; other errors → retry
  next scan.
- Cold cache (all null & something missing): extract in a pool of `clamp(cores,1,4)` threads, wait ≤5 min,
  patch paths, resolve artist images synchronously. Warm + `isFast`: extract missing art + artist images on a
  background thread and merge into `getExternalFilesDir(null) ?: filesDir` `/artist_images.json`.
  Not fast: artist images resolved inline per first-seen artist.
- Artist split regex: `[,;/&\\]|\s+(?:feat\.?|ft\.?|featuring|vs\.?|with|[xX\+])\s+` (case-insens.), trim
  brackets, dedupe case-insensitively, fallback "unknown artist".
- Artist image search: from song's folder upward until scan root: `artist.{jpg,png,webp,jpeg}` or
  `<lowerartist>.{jpg,png,webp,jpeg}`; or folder named like the artist → its first image file.
- Prune cache entries (`art_album_(\d+)\.(jpg|none|jpg\.tmp)`) for albums no longer present, only after a
  successful non-empty query.

`openFileFolder` strategies (folder = path if dir else parent): 1) Samsung My Files
`samsung.myfiles.intent.action.LAUNCH_MY_FILES` pkg `com.sec.android.app.myfiles` extra
`samsung.myfiles.intent.extra.START_PATH`; 2) ACTION_VIEW FileProvider folder URI with mime
`resource/folder` then `vnd.android.document/directory`, first resolver not containing "documentsui";
3) `ACTION_OPEN_DOCUMENT_TREE` with `EXTRA_INITIAL_URI = buildTreeDocumentUri("com.android.externalstorage.documents",
"primary:<rel>" or "<volume>:<sub>")`; 4) launch first installed of 17 file-manager packages (list in
manifest `<queries>`); 5) ACTION_VIEW `*/*` chooser; 6) `ACTION_INTERNAL_STORAGE_SETTINGS`.

### 13.3 `de.yurtemre.sonora/home_widget` — `update` (§12).

### 13.4 Other native bits
- `MainActivity` extends `AudioServiceActivity`, `singleTop`, `taskAffinity=""`, edge-to-edge
  (`setDecorFitsSystemWindows(false)`), keeps `ic_close`/`ic_launcher_monochrome` from R8 shrinking.
- FileProvider authority `${applicationId}.fileprovider`, paths: external ".", cache ".", files ".", root ".".
- flutter_local_notifications receivers (scheduled/boot) — unused by sleep timer (only immediate shows).
- `usesCleartextTraffic=true`, INTERNET (update checker / lyrics, other audit).

---

## 14. Test-pinned behaviours (keep as Kotlin unit tests)
- gapless_transition: track change during a window append or a remove updates the current item and the
  ReplayGain multiplier.
- replay_gain_playback: −6 dB → ×0.501; volume 0.5 → ×0.5·0.501; album mode falls back to track gain;
  untagged → unchanged; mode off → unchanged; library resync refreshes gains for queued current song.
- sleep_timer: new timer started during the previous stop's pause survives; fade restores user volume
  (0.8) not mid-fade value; ticks don't notify the main state; standard timer pauses and keeps queue;
  finish-song mode doesn't fade during countdown; extend 5 + 2 = 7 min; stop clears finishing flag.
- position_ticker: drop <1.5 s backward corrections; allow backward after discontinuity; same stream instance;
  immediate emission to new listener; tick only while playing & listened & foreground; catch-up on resume.
- home_widget_sync: field mapping, dedupe, retry on failure, `null` on stop, disabled off-Android.
- equalizer_and_mfx: preset values/titles, Bluetooth curve constants.
- native_bridge: getPackageInfo mapping & fallback, openUrl args/empty, shareFiles args, isDevBuild.
- settings_and_playback: defaults fade 10 s, finishSong false, etc.

---

## 15. Top porting risks / discrepancies
1. **Repeat-one Next/Prev restart the song** (just_audio) vs ExoPlayer advancing — needs ForwardingPlayer override for 1:1.
2. **Transport fades** (400 ms/20-step linear, token-cancelled, ≤0.05 → 1.0 quirk) must wrap every play/pause
   path (UI, notification, BT, widget, Auto) — Media3 calls `Player.play()` directly; implement in a ForwardingPlayer.
3. **Volume = userVolume × ReplayGain**, single choke point, clamp ≤1; gain applied before the transition is
   announced. With ExoPlayer, apply in `onMediaItemTransition` (may be ~a buffer late at gapless boundaries;
   consider an AudioProcessor for sample-accurate gain).
4. **Becoming-noisy setting is effectively ignored today** (just_audio always pauses) and resume-on-connect is
   racy — decide intended behaviour.
5. **Pause-on-duck** isn't supported by Media3's built-in focus handling.
6. **Foreground while paused** (`androidStopForegroundOnPause=false`) differs from Media3 defaults; Close/
   dismiss must clear queue + last-played prefs, task-removed must not.
7. **Equalizer** must bind to ExoPlayer's audio session id and survive session-id changes; band math is
   relative to device min/max dB.
8. **Shuffle semantics** (only upcoming shuffled, original order restored, reorders lost) — don't use
   ExoPlayer shuffle order naively; MediaSession queue today only exposes the window.
9. **Android Auto art** uses file:// URIs (won't load in the car process) and labels are hard-coded English;
   flat "Songs" node with the whole library needs paging.
10. **Persisted prefs live in DataStore `FlutterSharedPreferences`** with list/double encodings — migration needed
    to keep users' last queue, MFX, volume.
11. Latent bugs worth not porting: reorder outside window restarts track; repeat-all wraps to window start;
    unshuffle uses stale broadcast position; empty voice search plays the first artist.
