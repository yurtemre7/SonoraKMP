# Smart shuffle, weekly mix and LAN Jam: design

Three features that make SonoraKMP feel personal without a server, an account or
any data leaving the phone. Each ships as its own pull request, in this order.

## Intent

SonoraKMP is a private player for music you own. Streaming apps win on
discovery and togetherness (Spotify: Smart Shuffle, Daylist, Jam). They need a
cloud to do it. We can offer the same feelings from what is already on the
phone: your listening history and the Wi-Fi you share with friends.

- **Smart shuffle**: shuffle that stops replaying what you skip.
- **Weekly mix**: a fresh 50-song list every Monday, from your own taste plus
  songs you have not given a chance yet.
- **Jam**: several phones in one room play the same queue in sync, each from
  its own copy of the files.

## Goals

- Everything runs on the device. No new network destination, no analytics.
- Smart shuffle is a setting, default off.
- The weekly mix is stable for the week and refreshes Monday 08:00 local time,
  with a visible countdown.
- A Jam works on a shared Wi-Fi without internet, reusing device sync's pairing
  and encryption.
- Quality: every feature has unit tests for its pure logic, English and
  Japanese strings, and sensible empty and error states.

## Non-goals

- No cloud recommendations, no listening-history upload, no accounts.
- No background refresh of the weekly mix while the app is closed (it
  regenerates on next open).
- No audio streaming between phones in this round (Jam plays local copies).
- No iOS.

## Anti-goals

- A weekly mix that reshuffles every time the app opens or stats change.
- A Jam that sounds out of sync, or that lets a stranger on the Wi-Fi join
  without the code.
- Smart shuffle that silently changes behaviour for people who did not turn it on.

## Constraints

- Android only, Kotlin Multiplatform: pure logic in `shared/commonMain`, with
  tests in `commonTest`; Android pieces in `androidApp`.
- Existing stats tables (`song_stats`: plays, skips, restarts, last played) are
  the only history. No per-day log yet.
- Device sync already provides: mDNS advertising and discovery
  (`_sonora-sync._tcp`, manual IP:port fallback), J-PAKE pairing with a 6-digit
  code, AES-256-GCM `SecureChannel` frames, and `SongMatcher`.
- All user-visible text in string resources, English and Japanese, sentence case.
  The product name is always "SonoraKMP".

## Approaches considered

**Smart shuffle.** Weighted random over the upcoming queue (chosen); or a
deterministic score sort (predictable but feels like sorted, not shuffled); or
plain shuffle with a skip filter (too weak).

**Weekly mix.** Generate once and persist for the week (chosen); or compute live
from stats (changes while you listen, defeats "weekly"); or seed a
deterministic generator by week number (stable in theory, but changes when the
library or stats change).

**Jam** (picked by the user: B).
- A. Remote control only: cheap, but only the host makes sound.
- **B. Synchronized local playback (chosen):** each phone plays its own copy;
  clock-synced start and drift correction; shared queue. Works offline.
- C. Host streams audio to guests: guests need no files, about three times the
  work. Possible later, after B.

## Design

### 1. Smart shuffle

- Setting `smart_shuffle` (boolean, default false) in Settings → Playback, with a
  one-line explanation. Included in the backup settings whitelist.
- `SmartShuffle.order(songs, stats, favourites, nowMs, random)` in
  `shared/.../queue`, pure and testable with an injected `Random`.
  Weighted random without replacement (Efraimidis-Spirakis keys `u^(1/w)`):
  - base weight 1;
  - skip-heavy songs (skips / (plays + skips) ≥ 0.5 with at least 3 events) × 0.3;
  - played in the last 24 h × 0.5;
  - favourite × 1.5;
  - fewer than 2 plays × 1.5.
  Then a single pass pulls forward a later song to avoid the same artist twice
  in a row when one exists within the next 5 entries.
- Used wherever shuffle reorders the upcoming queue (`shuffleOn`, `quickShuffle`),
  only when the setting is on. History and the current song stay untouched, as
  today. With the setting off, behaviour is exactly as before.
- Edge cases: songs without stats weigh 1; one-song queues and empty queues are
  returned unchanged; all-equal weights degrade to a plain shuffle.

### 2. Weekly mix

- `WeeklyMix` in `shared/.../library` (pure):
  - `weekStart(nowMs, zone)`: the most recent Monday 08:00 in local calendar time,
    not "now minus 7 × 24 h", so daylight-saving changes are correct. Before
    Monday 08:00 the current week is still the previous one.
  - `nextRefresh(nowMs, zone)`: the next Monday 08:00.
  - `build(songs, stats, favourites, nowMs, random, limit = 50)`:
    about 60% "known and liked" (plays, favourites, recency, few skips), about 30%
    "fresh" (added in the last 30 days, or played at most once), the rest filled
    from remaining songs; at most 3 songs per artist, relaxed when the library
    is too small to fill the list; no duplicates; shorter list if the library has
    fewer than 50 songs; empty library gives an empty list.
- Persisted as one DataStore entry: `{weekStartMs, songPaths[]}`. Paths, not ids,
  so the list survives re-indexing. On app start and on every resume, if the stored
  `weekStartMs` differs from the computed one, regenerate (this also covers a
  clock set backwards or a time-zone change).
- Songs removed mid-week are filtered out when the list is read; the list is not
  refilled until the next week.
- UI: a card at the top of the Playlists tab ("Weekly mix", cover collage, song
  count, countdown "Refreshes in 3 d 4 h"). It opens a detail screen like the
  smart-list screen (play, shuffle, add to queue). A countdown ticker runs once a
  minute only while the screen is visible; when it reaches zero it regenerates in
  place. "Save as playlist" creates a normal playlist named "Weekly mix · 24 Nov"
  with the current songs (the saved copy never changes).
- Not backed up, not device-synced: it is derived data and regenerates.
- Empty state: "Play a few songs and your weekly mix will fill up."

### 3. Jam (synchronized local playback over LAN)

Roles: one **host**, up to 7 **guests** (my call, 8 phones total).

- **Joining**: the host starts a Jam (from Now Playing, the queue, or the weekly
  mix). It advertises `_sonora-jam._tcp` on its own port (it can run next to device
  sync) and shows a fresh 6-digit code for each Jam. Guests pick the Jam from the
  discovered list and type the code; manual IP:port works when mDNS is blocked.
  No QR joining (decided by the user): the 6-digit code is the way in.
  Pairing reuses the J-PAKE flow and `SecureChannel` from device sync. A wrong
  code, a full Jam, or a different protocol version gives a clear error. The host
  can end the Jam or remove a guest.
- **Protocol**: new frame types over `SecureChannel` (versioned separately from
  sync): `JamHello`, `JamState` (queue as song descriptors, current index,
  play/pause, position, host timestamp), `JamAddRequest`, `JamControl` (guest
  skip/pause if the host allows), `Ping`/`Pong` for clock sync, `JamEnd`.
- **Song matching**: songs are described by title, artist, album and duration and
  matched on each guest with the same matcher backup restore uses. A guest that
  lacks a song shows it greyed as "not on this phone" and skips it silently so
  the Jam continues. (A later round may fetch it via device sync.)
- **Clock sync**: NTP-style ping/pong over the channel, taking the lowest-RTT
  samples of the first 8, re-measured every 30 s. Playback events carry host
  time; a guest converts to its own clock and schedules the start. Late
  correction: if the guest is off by more than 40 ms it nudges playback speed by
  up to ±2% instead of an audible seek; more than 800 ms triggers a seek.
- **Per-device latency**: a "Speaker delay" setting (−500..+500 ms, default 0)
  for Bluetooth speakers, remembered per device.
- **Permissions in a Jam**: the host chooses "Guests can add songs" (default on)
  and "Guests can skip and pause" (default off). Guests always control their own
  volume and can leave at any time.
- **Playback integration**: a guest's local queue is mirrored from the host while
  in a Jam; local controls that would diverge are hidden or mapped to requests.
  Leaving restores the guest's previous queue. A Jam runs in the existing
  foreground-service pattern so it survives the screen turning off.
- **Failure handling**: a dropped guest reconnects within 30 s with its session
  key or is removed; if the host drops, guests keep playing the current song and
  then stop with a "Jam ended" message; a screen lock or Wi-Fi change ends the
  guest's connection cleanly and shows a rejoin button.
- **Stats**: songs played in a Jam count as normal plays on each device.

## Calls made while checking the design (mine, not yet confirmed)

- **Jam security**: the code is new for every Jam and ephemeral; nothing is
  stored as a trusted device. Each guest runs its own J-PAKE round against the host
  with that code. The existing lockout (3 wrong codes, then a timed lock) is reused.
  A guest that drops reconnects with a per-session key, valid for 30 s.
- **Transport**: `DeviceSyncServer` serves one peer on a random port. The Jam adds
  its own `JamServer` with one `SecureChannel` per guest, reusing `JpakeHandshake`,
  `PairingCode` and `SecureChannel` unchanged. Frame types are added to a separate
  Jam codec, not to the sync one.
- **Player risk**: speed control (`setCustomSpeed`) exists. Precisely timed starts
  are the main technical risk, so the Jam begins with a spike on two real phones.
  Target: within 100 ms on Wi-Fi with wired or built-in speakers; the 40 ms
  correction threshold is a tuning value, not a promise. The speaker-delay setting
  handles Bluetooth.
- **Guest queue**: the mirrored Jam queue is not persisted. If the app is killed
  mid-Jam, the guest's previous queue comes back. In a Jam the guest's seek,
  reorder, remove, shuffle, repeat and sleep-timer controls are hidden or map to a
  request to the host.
- **Smart shuffle in a Jam**: the host orders the queue (its own setting); guests
  only mirror it. The weekly mix and playlist shuffles go through `quickShuffle`,
  so they follow the same setting.
- **Weekly-mix data**: `dateAddedMs` is already on `Song` and `lastPlayedAtMs` is
  epoch milliseconds, so no migration. Score for "known and liked":
  plays + 2 for a favourite, × recency decay (half-life 30 days), × 0.5 when the
  skip rate is at least 50%.
- **Jam stats**: a song a guest lacks is not counted at all. A skip caused by the
  host does not count as a skip on the guest; the guest's own skip does.
- **Naming**: the saved playlist's date uses the locale's short date format.

## Decisions made by the user

- Smart shuffle is a togglable setting.
- Weekly mix: up to 50 songs, includes new/unheard songs, can be saved as a copy,
  changes at the week's end, with a countdown; Monday 08:00.
- Jam via synchronized local playback (approach B), no streaming in this round.
- Jam joins with the 6-digit code only; no QR code, no camera permission.

## Left to the builder

- Exact weights and the artist-spacing window (starting values above, tunable).
- Visual design of the weekly card and Jam screens within the "Sleeve" style.
- Exact framing of the new protocol frames and the reconnect handshake.
- Test strategy for clock sync (fake clock and fake channel are expected).

## Order of work

1. Smart shuffle setting and algorithm (small).
2. Weekly mix (medium).
3. Jam (large; split into: protocol and clock sync in shared code, host session,
   guest session, UI, polish).
