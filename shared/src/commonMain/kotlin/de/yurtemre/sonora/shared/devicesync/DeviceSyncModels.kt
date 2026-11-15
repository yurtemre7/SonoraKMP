package de.yurtemre.sonora.shared.devicesync

import kotlinx.serialization.Serializable

/** Raised for any malformed, oversized or unexpected device sync wire data. */
class DeviceSyncProtocolException(message: String) : Exception(message)

@Serializable
enum class FileKind { Audio, Lyrics, Cover }

@Serializable
data class AudioMeta(val title: String, val artist: String, val album: String, val durationMs: Long)

/** [relPath] uses `/` and is relative to the sender's music root. */
@Serializable
data class ManifestFile(
    val id: Int,
    val relPath: String,
    val size: Long,
    val modifiedMs: Long,
    val kind: FileKind,
    val audio: AudioMeta? = null,
)

@Serializable
data class SongCounters(
    val plays: Int = 0,
    val skips: Int = 0,
    val restarts: Int = 0,
    val lastPlayedAtMs: Long? = null,
)

@Serializable
data class SyncStats(
    val songs: Map<String, SongCounters> = emptyMap(),
    val weekday: Map<Int, Int> = emptyMap(),
    val playlistPlays: Map<String, Int> = emptyMap(),
    val completeListens: Int = 0,
    val shuffleSessions: Int = 0,
    val firstPlayedPath: String? = null,
    val firstPlayedDate: String? = null,
    val recentlyPlayed: Map<String, Long> = emptyMap(),
)

@Serializable
data class SyncBundle(val backupJson: String, val stats: SyncStats)

@Serializable
data class Hello(val protocol: Int, val deviceId: String, val deviceName: String)

@Serializable
data class Manifest(
    val protocol: Int,
    val deviceId: String,
    val deviceName: String,
    val bundle: SyncBundle,
    val files: List<ManifestFile>,
)

@Serializable
data class FingerprintRequest(val ids: List<Int>)

/** Hex SHA-256 fingerprints keyed by file id. */
@Serializable
data class Fingerprints(val byId: Map<Int, String>)

@Serializable
data class Fetch(val ids: List<Int>)

@Serializable
data class FileStart(val id: Int, val size: Long)

@Serializable
data class FileEnd(val id: Int, val sha256Hex: String)

@Serializable
data class ErrorFrame(val code: String, val message: String)
