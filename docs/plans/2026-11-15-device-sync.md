# Device sync — implementation plan

Spec: `docs/SPEC.md` §4.12 (DSYNC-01..14), decisions D-69..D-73, risks R-17..R-20, files §5.4. The spec is binding;
this plan is how we get there. Conventions from the spec apply (`S/`, `SA/`, `A/`, `AR/` path prefixes).

## Global Constraints

- **Build & test:** `ANDROID_HOME=/opt/android-sdk ./gradlew <tasks> --console=plain` from the repo root. Every task
  must leave `./gradlew assembleDebug testDebugUnitTest lintDebug` green, plus `./scripts/check_hardcoded_strings.sh`
  and `./scripts/check_product_name.sh`. Maven Central sometimes answers 429 through the proxy: on a dependency
  resolution failure wait ~60 s and retry (add `--refresh-dependencies` once if Gradle cached a false "not found").
- **No Google Play Services**, no Nearby Connections, no Firebase, no ML Kit (D-70).
- **Layering:** pure logic in `S/devicesync/` (commonMain, kotlinx.serialization, tests in `shared/src/commonTest` or
  `shared/src/androidHostTest`); JVM networking/crypto in `SA/devicesync/` (tests in `androidHostTest`); Android
  platform code (NSD, SAF, MediaStore, service, UI) in `A/devicesync/` and `A/ui/screens/devicesync/`.
- **Naming:** the feature is "device sync" everywhere (code: `DeviceSync*`, package `devicesync`); never call it
  "sync" alone — that word means the library scan (`LibraryRepository.sync()`).
- **Strings:** every user-visible string is a resource in `AR/values/strings_devicesync.xml` **and**
  `AR/values-ja/strings_devicesync.xml` (natural Japanese, same keys and format args — `StringsParityTest` enforces
  it). No hardcoded UI text.
- **Wire constants (exact):** NSD service type `_sonora-sync._tcp`; protocol version `1`; control frame max 4 MiB;
  file chunk 64 KiB; code = 6 decimal digits from `SecureRandom`; code lifetime 5 min; max 3 failed handshakes
  per code, then a new code; fingerprint = SHA-256 over (size as 8-byte big-endian ‖ first 64 KiB ‖ last 64 KiB);
  space margin 200 MB (209 715 200 bytes); temp suffix `.sonora-part`.
- **Allowed file types (exact, lowercase extension):** audio = `DirectoryScanner.AUDIO_EXTS`
  (`mp3 m4a mp4 aac flac ogg opus wav wma amr 3gp ts mkv mid midi`); lyrics = `lrc txt` (only when a sibling audio
  file with the same base name exists on the sender); images = `jpg jpeg png webp` (only names in
  `AndroidLibraryScanner.FOLDER_COVER_NAMES` ∪ `DirectoryScanner.COVER_NAMES`, case-insensitive).
- **Device-specific settings never synced (exact stored keys):** `scan_folder_path`, `scan_folder_uri`, `volume`,
  `onboarding_completed`, `last_update_check_ms`, `update_rate_limit_reset_ms`, `last_sync_timestamp`,
  `last_sync_duration_ms`, `postpone_sync_until`, `colorless_artwork_paths`, every `last_played_*` key,
  `repeat_mode`, plus any key starting with `device_sync_`.
- **Security:** the code is only ever a J-PAKE password (BouncyCastle lightweight API
  `org.bouncycastle.crypto.agreement.jpake`, never the JCA "BC" provider); session keys via HKDF-SHA256; frames
  AES-256-GCM with 96-bit counter nonces, separate keys per direction; never log the code or keys.
- **Never overwrite or delete without the rules in DSYNC-11/12.** Paths from the network are untrusted.
- **Commits:** conventional commits (`feat(device-sync): …`), end every message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01RLkwAbsLCs2Wdwh8yHDxLs`. No model names elsewhere.
- **Do not push**; the controller pushes.

## Task 1: Wire model and frame codec (pure)

Files: create `S/devicesync/DeviceSyncModels.kt`, `S/devicesync/FrameCodec.kt`; tests
`shared/src/commonTest/kotlin/de/yurtemre/sonora/shared/devicesync/FrameCodecTest.kt`.

Models (`@Serializable`):
- `enum class FileKind { Audio, Lyrics, Cover }`
- `data class AudioMeta(title: String, artist: String, album: String, durationMs: Long)`
- `data class ManifestFile(id: Int, relPath: String, size: Long, modifiedMs: Long, kind: FileKind, audio: AudioMeta? = null)` — `relPath` uses `/`, relative to the sender's music root.
- `data class Hello(protocol: Int, deviceId: String, deviceName: String)`
- `data class Manifest(protocol: Int, deviceId: String, deviceName: String, bundle: SyncBundle, files: List<ManifestFile>)`
- `data class FingerprintRequest(ids: List<Int>)`, `data class Fingerprints(byId: Map<Int, String>)` (hex)
- `data class Fetch(ids: List<Int>)`, `data class FileStart(id: Int, size: Long)`, `data class FileEnd(id: Int, sha256Hex: String)`, `data class ErrorFrame(code: String, message: String)`
- `SyncBundle` is defined in Task 4; for this task declare it here as `@Serializable data class SyncBundle(backupJson: String, stats: SyncStats)` and `SyncStats` exactly as Task 4 specifies (move nothing later).

Frame codec over `kotlinx.io`-free plain byte arrays (works on any `ByteArray` stream abstraction the session layer
uses): a frame is `type: UByte` + `length: Int (big-endian, ≥ 0)` + payload. Types: `1 Hello, 2 Manifest, 3 Fetch,
4 FileStart, 5 Chunk (raw bytes), 6 FileEnd, 7 Error, 8 Done, 9 FingerprintRequest, 10 Fingerprints`. Control payloads are UTF-8 JSON
(`Json { ignoreUnknownKeys = true; encodeDefaults = true }`). API:
`sealed interface Frame` with one subtype per type (`Chunk(bytes: ByteArray)`, `Done` object),
`FrameCodec.encode(frame): ByteArray`, `FrameCodec.decodeHeader(bytes: ByteArray): Pair<Int, Int>` (type, length)
and `FrameCodec.decodeBody(type, payload): Frame`; `FrameCodec.MAX_CONTROL = 4 * 1024 * 1024`,
`FrameCodec.CHUNK = 64 * 1024`. Reject: unknown type, negative length, control length > MAX_CONTROL, chunk length
> CHUNK, malformed JSON → `DeviceSyncProtocolException(message)`.

Tests: round-trip every frame type; each rejection case; a 64 KiB chunk passes, 64 KiB + 1 fails.

## Task 2: Path safety and file classification (pure)

Files: `S/devicesync/SyncPaths.kt`, test `.../devicesync/SyncPathsTest.kt`.

- `SyncPaths.sanitize(relPath: String): String?` — returns a normalized relative path or null. Rules: split on `/`
  (also treat `\` as separator); reject empty, absolute (leading `/` or `X:`), any segment `.` / `..` / empty,
  control chars (< 0x20, 0x7F), any of `<>:"|?*`, segment UTF-8 length > 255 bytes, depth > 16, total > 1024 chars;
  trim trailing dots/spaces per segment (reject if that empties it).
- `SyncPaths.kindOf(relPath): FileKind?` per the Global Constraints allowed types (cover names case-insensitive).
- `SyncPaths.extension(relPath)` lowercase, `SyncPaths.baseName`, `SyncPaths.parent`.
- `SyncPaths.collisionName(relPath, attempt: Int)`: `Song.mp3` → `Song (2).mp3`, `(3)` …
- Export the exact allowed-extension sets as constants (shared with Task 9/10).

Tests: traversal (`../x`, `a/../../x`, `/abs`, `C:x`, `a//b`), unicode names, long segment, deep path, reserved
chars, kinds for every allowed and a few disallowed extensions (`apk`, `exe`, `sh`, `html`), collision naming.

## Task 3: Transfer planner (pure)

Files: `S/devicesync/TransferPlanner.kt`, test `.../devicesync/TransferPlannerTest.kt`.

Inputs: `files: List<ManifestFile>`; `ReceiverIndex(existing: Map<String, Long>` (sanitized relPath → size of files
already in the destination), `library: List<ReceiverSong>)` where
`ReceiverSong(songId: Long, relPath: String?, title, artist, album, durationMs, size: Long?)` (`relPath` null when the
song is outside the destination). Output `TransferPlan(items: List<PlanItem>)`,
`PlanItem(file: ManifestFile, status: Status, replaces: ReceiverSong? = null, needsFingerprint: Boolean = false)`,
`enum Status { New, Replace, Present }`.

Rules (DSYNC-08, D-72): file whose path fails `SyncPaths.sanitize` or `kindOf` → dropped (listed in
`TransferPlan.rejected`). Present when `existing[relPath] == size`. Audio: tag match = same
`trim().lowercase()` title, artist, album and |Δduration| ≤ 2000 ms (closest wins, each receiver song claimed once,
ties lowest songId — same as `SongMatcher` pass 2); match with same extension → Present; with different extension
→ Replace (`replaces` = that song). Same relPath but different size → New with `needsFingerprint = true` when some
receiver file has equal size, otherwise New (target name resolved later by collision naming). Default selection:
New and Replace. `TransferPlan.bytesNeeded(selectedIds)`, `bytesFreedAfter(selectedIds)` (sum of replaced songs'
sizes), `fits(selectedIds, freeBytes)` = needed + 200 MB ≤ free. Grouping helpers for the UI:
`groupsByKind()` and `audioFormats()` (extension → count, bytes).

Tests: MP3→FLAC replace, same song same format present, renamed but same size flagged for fingerprint, lyrics/cover
present by path, unsafe path rejected, claim-once tie-break, space math with margin.

## Task 4: Stats as a difference (pure)

Files: `S/devicesync/StatsDelta.kt`, test `.../devicesync/StatsDeltaTest.kt`.

`@Serializable data class SyncStats(songs: Map<String, SongCounters>, weekday: Map<Int, Int>,
playlistPlays: Map<String, Int>, completeListens: Int, shuffleSessions: Int, firstPlayedPath: String?,
firstPlayedDate: String?, recentlyPlayed: Map<String, Long>)` keyed by the **sender's absolute file path**
(stable across MediaStore re-index); `SongCounters(plays, skips, restarts, lastPlayedAtMs: Long?)`.
(`recentlyPlayed` = path → last played ms; it is the `lastPlayedAtMs` view, keep it consistent.)

- `StatsDelta.difference(now: SyncStats, baseline: SyncStats?): SyncStats` — per counter `max(0, now − base)`; if
  `now.completeListens < baseline.completeListens` (sender reset) → treat baseline as null (full `now`, never
  negative). Dates/first-played are not differences: pass `now`'s values through.
- `StatsDelta.toSnapshotDelta(delta: SyncStats, pathToReceiverId: Map<String, Long>): StatsSnapshot` — unknown paths
  dropped; playlists by id as-is.
- `StatsDelta.merge(receiver: StatsSnapshot, delta: StatsSnapshot): StatsSnapshot` — counters summed;
  `lastPlayedAtMs` = max; first played = earlier of the two by date (receiver wins ties).
- `SyncStats.fromSnapshot(snapshot, pathOf: (Long) -> String?)` helper for the sender.

Tests (spec acceptance): sync twice with no new listens adds 0; 3 new plays add exactly 3; sender reset → no
subtraction and baseline replaced; unmatched paths dropped; first/last played rules.

## Task 5: Device sync data repository (shared, Room + DataStore)

Files: `S/repo/api/DeviceSyncDataRepository.kt`, `S/repo/impl/DeviceSyncDataRepositoryImpl.kt`,
`S/devicesync/DeviceSyncSettings.kt` (excluded keys), Koin binding in `SA/di/SharedModule.kt`; extend
`StatsRepository` with `suspend fun mergeDelta(delta: StatsSnapshot)` (one transaction, uses `StatsDelta.merge`
semantics) and implement it in `StatsRepositoryImpl`; tests in `shared/src/androidHostTest/.../repo/DeviceSyncDataTest.kt`
following `RepositoriesTest.kt` setup.

API:
- `suspend fun deviceId(): String` — random UUID persisted in `files/device_sync/device_id` (FileStore), created once.
- `suspend fun exportBundle(): SyncBundle` — backup v1 JSON via `BackupCodec.buildExport` with **stats omitted**
  (`stats = null` / empty) and the device-specific keys removed from settings, plus `SyncStats` from the stats
  snapshot (paths from the library).
- `suspend fun applyBundle(bundle: SyncBundle, senderDeviceId: String): DeviceSyncApplyResult` — parse with
  `BackupCodec.parse`, plan with `RestorePlanner` (library-empty is not an error here: return counts 0 and skip),
  strip device-specific keys from `settingWrites`/`settingRemovals`, apply like `BackupRepositoryImpl` (DataStore then
  one Room transaction, rollback DataStore on DB failure, stats recording paused), then stats: load baseline
  `files/device_sync/peers/<senderDeviceId>.json`, `difference`, map paths via `SongMatcher` (bundle song refs →
  library), `mergeDelta`, write new baseline = `bundle.stats`. Idempotent (DSYNC-05 acceptance).
- `suspend fun relinkReplaced(oldToNew: Map<Long, Long>)` — moves favorites, playlist entries and stats from old song
  ids to new ones in one transaction (reuse the DAO relink helpers in `Daos.kt`).

Refactor rather than copy: extract the shared read/apply helpers out of `BackupRepositoryImpl` into an internal
helper both use. Backup behaviour must not change (existing backup tests stay green).

Tests: export omits excluded keys; apply twice → identical DB dump; stats delta via a second export with 3 new
plays adds 3; baseline file written; relinkReplaced moves all three kinds.

## Task 6: Pairing and encrypted channel (JVM)

Files: add `bouncycastle-bcprov = { module = "org.bouncycastle:bcprov-jdk18on", version = "<latest 1.8x on Maven Central>" }`
to `gradle/libs.versions.toml` and to `shared` androidMain dependencies; `SA/devicesync/PairingCode.kt`,
`SA/devicesync/JpakeHandshake.kt`, `SA/devicesync/SecureChannel.kt`; tests in
`shared/src/androidHostTest/.../devicesync/SecureChannelTest.kt`. Keep R8 working (add keep rules only if the release
build needs them; prefer none).

- `PairingCode.generate(random: SecureRandom = SecureRandom()): String` — 6 digits, zero-padded.
- `JpakeHandshake.run(input: DataInputStream, output: DataOutputStream, code: String, role: Role, myId: String): ByteArray`
  — full 3-round J-PAKE (`JPAKEPrimeOrderGroups.NIST_3072`, SHA-256 digest), round 3 key confirmation; returns 32
  bytes keying material; wrong code → `DeviceSyncAuthException`. Payloads exchanged as length-prefixed byte arrays.
- `SecureChannel(input, output, keyingMaterial, role)` — HKDF-SHA256 (BC `HKDFBytesGenerator`, info
  `"sonora-sync v1 c2s"` / `"sonora-sync v1 s2c"`) → two AES-256 keys + two 4-byte nonce prefixes; `send(frame: Frame)` /
  `receive(): Frame` encrypt each encoded frame as one GCM record `[len:int][ciphertext+tag]`, nonce = prefix ‖
  64-bit counter; reject replays/out-of-order (counter must match), tag failure → `DeviceSyncAuthException`.

Tests (piped streams in two coroutines/threads): right code → both sides derive equal keys and exchange frames;
wrong code → both fail; flipped ciphertext byte → failure; reordered record → failure; code generator always 6
digits.

## Task 7: Session server and client over TCP (JVM)

Files: `SA/devicesync/DeviceSyncServer.kt`, `SA/devicesync/DeviceSyncClient.kt`, `SA/devicesync/SyncFileSource.kt`,
`SA/devicesync/SyncFileSink.kt` (interfaces); tests `.../devicesync/DeviceSyncSessionTest.kt` on loopback.

- `interface SyncFileSource { fun open(file: ManifestFile): InputStream }`.
- `DeviceSyncServer(manifestProvider: suspend () -> Manifest, source: SyncFileSource, clock: () -> Long)`:
  `start(): Int` (binds `ServerSocket(0)` on all interfaces, returns port), `val code: StateFlow<String>`,
  `val state: StateFlow<ServerState>` (`Waiting`, `Paired(peerName)`, `Sending(fileId, bytesSent, totalBytes)`,
  `Done`, `Failed(reason)`), `stop()`. Rotates the code after 5 min or 3 failed handshakes (Global Constraints).
  Serves one receiver at a time; after pairing: send `Manifest`; on `FingerprintRequest` reply `Fingerprints` (Global Constraints fingerprint, ids from its manifest only); loop on `Fetch` → for each id in its own manifest
  stream `FileStart`, `Chunk`s, `FileEnd(sha256)`; unknown id → `ErrorFrame("unknown_file")`, never reads a path
  not in its manifest; `Done` from client ends the session.
- `interface SyncFileSink { fun begin(file: ManifestFile): OutputStream; fun commit(file, sha256Hex); fun abort(file) }`.
- `DeviceSyncClient`: `suspend fun connect(host: String, port: Int, code: String, hello: Hello): Manifest`,
  `suspend fun fetch(ids: List<Int>, sink: SyncFileSink, onProgress: (TransferProgress) -> Unit)` — verifies size
  and SHA-256 per file before `commit`, mismatch → `abort` + per-file failure (continue with the next file),
  `suspend fun fingerprints(ids: List<Int>): Map<Int, String>`, `close()` sends `Done`. All blocking IO on `Dispatchers.IO`; socket timeouts 30 s; cancellation closes the socket.
- `TransferProgress(fileId, fileBytes, fileSize, totalBytes, totalSize, filesDone, filesTotal, bytesPerSecond)`.

Tests: full session on loopback with an in-memory source/sink (3 files incl. empty file, 200 KiB file), fingerprint request, wrong code
counted and third failure rotates code, checksum mismatch aborts only that file, unknown id rejected, cancellation.

## Task 8: Discovery (Android)

Files: `A/devicesync/DeviceSyncDiscovery.kt`; manifest permissions `ACCESS_WIFI_STATE`,
`CHANGE_WIFI_MULTICAST_STATE`.

- `advertise(port: Int, deviceName: String): AutoCloseable` — `NsdManager.registerService`, type `_sonora-sync._tcp`,
  service name `Sonora-<4 hex of deviceId>`, TXT `v=1`, `name=<deviceName>`.
- `discover(): Flow<DiscoveredSender>` (`name`, `host`, `port`) — `discoverServices` + resolve (use
  `registerServiceInfoCallback` on API 34+, `resolveService` below; serialize resolves), holds a
  `WifiManager.MulticastLock` while collecting, releases on cancellation.
- `localIpv4(): String?` for the manual "IP:port" fallback shown on the sender screen.
- `parseManual(text: String): Pair<String, Int>?` (pure, unit-tested in `androidApp/src/test`).

## Task 9: Sender file source (Android)

Files: `A/devicesync/SenderManifestBuilder.kt`, `A/devicesync/AndroidSyncFileSource.kt`; test
`androidApp/src/test/.../devicesync/SenderManifestBuilderTest.kt` (pure parts).

- Music root: folder mode = `scan_folder_uri` tree (walk with `DocumentsContract` children queries); MediaStore mode
  = the common parent directory of all library songs' paths (walk with `java.io.File`, plus MediaStore fallback for
  opening files that `File` can't read on API 30+).
- Include: every library song under the root (audio meta from `LibraryRepository.songs`), lyric sidecars
  (`<base>.lrc` / `.txt` next to an included audio file), cover images by name (Global Constraints). Everything
  passes `SyncPaths.sanitize` + `kindOf`; anything else is skipped. Stable ids = index in the sorted relPath list.
- `AndroidSyncFileSource.open(file)` — opens only paths recorded by the builder (map id → Uri/File).
- `bundle` from `DeviceSyncDataRepository.exportBundle()`.

## Task 10: Receiver destination, plan inputs, writing (Android)

Files: `A/devicesync/DestinationFolder.kt`, `A/devicesync/ReceiverIndexBuilder.kt`, `A/devicesync/SafSyncFileSink.kt`,
`A/devicesync/Fingerprints.kt`; extend `A/permissions/FolderPicker.kt` with `persistWritable(...)` (read + write
flags). Tests for pure parts in `androidApp/src/test/.../devicesync/`.

- Destination: default `scan_folder_uri` if it has a persisted **write** grant; otherwise the UI asks with
  `OpenDocumentTree` + `EXTRA_INITIAL_URI` (that uri, else `Music/`). Free space via `StatFs` on the tree's resolved
  path (`TreeUriPaths`), fallback `StorageManager.getAllocatableBytes`.
- `ReceiverIndexBuilder` → `ReceiverIndex` (Task 3): walk the destination tree (relPath → size) and library songs
  with relPath relative to the destination (null outside).
- `Fingerprints.of(read: (offset, length) -> ByteArray, size)` per the Global Constraints formula, cached in
  `files/device_sync/fingerprints.json` keyed by `path|size|mtime`. For plan items with `needsFingerprint`, the
  controller asks the sender (`DeviceSyncClient.fingerprints(ids)`) and compares with the receiver's same-size
  files: equal → item becomes Present, otherwise stays New (DSYNC-08).
- `SafSyncFileSink`: creates parent folders once (cache by relPath), writes `<name>.sonora-part` via
  `DocumentsContract.createDocument`, `commit` renames to the final name (collision → `SyncPaths.collisionName`,
  never overwrite), `abort` deletes the part file; leftover `.sonora-part` files from a previous run are deleted
  at start.

## Task 11: Transfer service, apply and replace (Android)

Files: `A/devicesync/DeviceSyncService.kt` (foreground, `foregroundServiceType="dataSync"`), `A/devicesync/DeviceSyncController.kt`
(process-wide state holder, Koin single), manifest entries + `FOREGROUND_SERVICE_DATA_SYNC` permission, notification
channel `device_sync` (strings).

- Controller states: `Idle`, `Sending(code, ip:port, ServerState)`, `Discovering(list)`, `Connecting`,
  `Planning(plan, freeBytes, destination)`, `Transferring(TransferProgress)`, `Applying`, `Finished(summary)`,
  `Failed(reason)`; one `StateFlow<DeviceSyncState>` the UI observes; commands `startSending()`,
  `startReceiving()`, `connect(sender | manual, code)`, `startTransfer(selectedIds)`, `cancel()`.
- Service holds a high-performance `WifiLock` + partial `WakeLock` while sending/transferring, notification with
  files/bytes/speed/ETA and Cancel action; stops itself when idle.
- After the transfer: library `sync()`; for Replace items whose new file was committed: delete the old file (SAF
  `DocumentsContract.deleteDocument` in folder mode; MediaStore mode on API 30+ → controller exposes a pending
  `IntentSender` from `MediaStore.createDeleteRequest` for the UI to launch, batched), then `relinkReplaced(old→new)`
  (new ids found by path after the library sync) **before** deleting; declined deletion keeps both files.
- Then `applyBundle(bundle, senderDeviceId)`; summary = files copied/replaced/failed, bytes, playlists/favorites/
  settings counts, stats added.
- Resume: on error the controller can `retry()` → reconnect with the same code (if still valid) and re-plan
  (already-written files are Present).

## Task 12: UI (Android)

Files: `A/ui/screens/devicesync/DeviceSyncRoute.kt` (+ small files per screen), `NavKeys.kt` (`SettingsDeviceSync`),
`SettingsRoute.kt` entry (between Backup and Updates, icon `Icons.Rounded.Devices` or similar), `SonoraAppShell.kt`
entry, strings en + ja.

Screens (Material 3 expressive, follow existing settings screens' components):
1. Start: two big choices *Send from this phone* / *Receive on this phone*, short explanation (same Wi-Fi, one way).
2. Sender: large 6-digit code (monospace, grouped 3+3), expiry countdown, "Waiting for the other phone…",
   manual address `IP:port` small; paired state shows receiver name and progress; Stop.
3. Receiver – connect: list of discovered senders (or searching indicator), code field (numeric, 6 digits, auto
   submit), "Enter address manually" expandable field; errors: wrong code (attempts left), not found, version mismatch.
4. Receiver – destination: shows folder; "Change folder" (OpenDocumentTree with write); permission request flow.
5. Receiver – selection: summary chips (New n · Replace n · Present n), tabs/sections Audio (format chips with
   counts), Lyrics, Covers; folder-grouped list with checkboxes, Select all / none, Replace rows show "MP3 → FLAC",
   Present rows greyed and disabled; bottom bar "Needs 3.2 GB · 18.4 GB free" + Start (disabled when it doesn't fit,
   with reason).
6. Receiver – progress & result: progress (files, bytes, speed, ETA), Cancel; MediaStore delete confirmation
   launcher; result summary with failed files list and Done.
Accessibility: all icons described, 48 dp targets, code field announces digits.

## Task 13: Hotspot fallback with QR (Android, phase 4)

Files: add `zxing-core` (com.google.zxing:core — Apache-2.0, not a Google Play service) and
`com.journeyapps:zxing-android-embedded` to the version catalog / androidApp; `A/devicesync/HotspotHost.kt`,
`A/devicesync/HotspotJoiner.kt`, `A/devicesync/SyncQr.kt`; permissions `NEARBY_WIFI_DEVICES`
(`usesPermissionFlags="neverForLocation"`), `ACCESS_FINE_LOCATION` (`maxSdkVersion="32"`), `CAMERA`; strings.

- QR payload (pure, tested): `sonora-sync:1?h=<ip>&p=<port>&c=<code>[&s=<ssid>&k=<passphrase>]` (URL-encoded).
- Sender screen gains a QR of the LAN payload and a "No shared Wi-Fi? Use phone hotspot" action:
  `WifiManager.startLocalOnlyHotspot` (request permission first), then QR with ssid/passphrase.
- Receiver gains "Scan QR": LAN payload → connect directly; hotspot payload → `WifiNetworkSpecifier`
  (API 29+; below 29 show "needs Android 10") + `ConnectivityManager.requestNetwork`, `bindProcessToNetwork`
  for the session, unbind after.

## Task 14: Docs and changelog

Update `docs/SPEC.md` §4.12 (mark built, note the DSYNC-08 ambiguous-case implementation from Task 10, any
deviations), `docs/TASKS.md` if it lists features, `CHANGELOG.md` / assets changelog (follow existing format and
whether the repo keeps an Unreleased section), README feature list if present.
