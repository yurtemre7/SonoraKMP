package de.yurtemre.sonora.shared.backup

import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** Backup v1 encode / parse / validate, byte-semantics compatible with Flutter 1.28.x (BAK-01, BAK-02, BAK-05). */
object BackupCodec {
    const val FORMAT = "sonora-backup"
    const val VERSION = 1

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** `sonora-backup-YYYY-MM-DD.json`. */
    fun defaultFileName(year: Int, month: Int, day: Int): String =
        "sonora-backup-$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}.json"

    /** Strict UTF-8 decode with BOM strip; malformed bytes → [BackupError.InvalidJson]. */
    fun decodeFile(bytes: ByteArray): String {
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw BackupError.InvalidJson("Invalid UTF-8: ${e.message}")
        }
        return stripBom(text)
    }

    private fun stripBom(s: String) = if (s.startsWith('﻿')) s.substring(1) else s

    // ── Export ──────────────────────────────────────────────────────────────

    /**
     * Builds the export document (BAK-01, Flutter `buildBackup`). [storedSettings] are KMP stored values
     * (see [SettingsWhitelist]); favorites albums/artists maps as stored.
     */
    fun buildExport(
        library: List<Song>,
        playlists: List<Playlist>,
        favoriteAlbums: Map<String, Long>,
        favoriteArtists: Map<String, Long>,
        stats: StatsSnapshot,
        storedSettings: Map<String, SettingValue>,
        appVersion: String,
        nowMs: Long,
    ): BackupDocument {
        if (library.isEmpty()) throw BackupError.LibraryEmpty()
        val byId = library.associateBy { it.id }
        val referenced = LinkedHashSet<Long>()
        fun keep(id: Long): Boolean = (id in byId).also { if (it) referenced.add(id) }

        val outPlaylists = playlists.map { p ->
            BackupPlaylist(p.id, p.name, p.description, p.songIds.filter(::keep))
        }
        val favorites = LinkedHashMap<Long, Long?>()
        for (s in library) if (s.isFavorite && keep(s.id)) favorites[s.id] = s.favoriteDateMs
        val raw = BackupStats.fromSnapshot(stats)
        val outStats = raw.copy(
            songPlayCounts = raw.songPlayCounts.filterKeys(::keep),
            songSkipCounts = raw.songSkipCounts.filterKeys(::keep),
            songRestartCounts = raw.songRestartCounts.filterKeys(::keep),
            songLastPlayedAtMs = raw.songLastPlayedAtMs.filterKeys(::keep),
            firstPlayedSongId = raw.firstPlayedSongId.takeIf { it < 0 || keep(it) } ?: -1,
        )
        val songs = LinkedHashMap<Long, BackupSongRef>()
        for (id in referenced) {
            val s = byId.getValue(id)
            songs[id] = BackupSongRef(s.id, s.filePath, s.title, s.artist, s.album, s.durationMs)
        }
        return BackupDocument(
            createdAtMs = nowMs, appVersion = appVersion, songs = songs,
            settings = SettingsWhitelist.toBackup(storedSettings), hasSettings = true,
            playlists = outPlaylists, favoriteSongs = favorites,
            favoriteAlbums = favoriteAlbums, favoriteArtists = favoriteArtists, stats = outStats,
        )
    }

    /** Pretty 2-space JSON in Flutter's key order. */
    fun encode(doc: BackupDocument): String = pretty.encodeToString(JsonElement.serializer(), toJson(doc))

    fun toJson(doc: BackupDocument): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("createdAt", isoUtc(doc.createdAtMs))
        put("appVersion", doc.appVersion)
        put("songs", JsonArray(doc.songs.values.map { r ->
            buildJsonObject {
                put("id", r.id); put("path", r.path); put("title", r.title)
                put("artist", r.artist); put("album", r.album); put("durationMs", r.durationMs)
            }
        }))
        put("settings", JsonObject(doc.settings.mapValues { settingJson(it.value) }))
        put("playlists", JsonArray((doc.playlists ?: emptyList()).map { p ->
            buildJsonObject {
                put("id", p.id); put("name", p.name); put("description", p.description)
                put("songIds", JsonArray(p.songIds.map(::JsonPrimitive)))
            }
        }))
        put("favorites", buildJsonObject {
            put("songs", JsonArray((doc.favoriteSongs ?: emptyMap()).map { (id, date) ->
                buildJsonObject { put("id", id); put("favoriteDateMs", date) }
            }))
            put("albums", longMap(doc.favoriteAlbums ?: emptyMap()))
            put("artists", longMap(doc.favoriteArtists ?: emptyMap()))
        })
        val s = doc.stats ?: BackupStats()
        put("stats", buildJsonObject {
            put("complete_song_listens", s.completeSongListens)
            put("first_played_song_id", s.firstPlayedSongId)
            put("first_played_date", s.firstPlayedDate)
            put("song_play_counts", longMap(s.songPlayCounts.mapKeys { it.key.toString() }))
            put("playlist_play_counts", longMap(s.playlistPlayCounts))
            put("weekly_play_counts", longMap(s.weeklyPlayCounts.mapKeys { it.key.toString() }))
            put("shuffle_session_starts", s.shuffleSessionStarts)
            put("song_skip_counts", longMap(s.songSkipCounts.mapKeys { it.key.toString() }))
            put("song_restart_counts", longMap(s.songRestartCounts.mapKeys { it.key.toString() }))
            put("song_last_played_at_ms", longMap(s.songLastPlayedAtMs.mapKeys { it.key.toString() }))
        })
    }

    private fun longMap(m: Map<String, Long>) = JsonObject(m.mapValues { JsonPrimitive(it.value) })

    private fun settingJson(v: SettingValue): JsonElement = when (v) {
        is SettingValue.Bool -> JsonPrimitive(v.value)
        is SettingValue.Int -> JsonPrimitive(v.value)
        is SettingValue.Decimal -> JsonPrimitive(v.value)
        is SettingValue.Str -> JsonPrimitive(v.value)
        is SettingValue.StrList -> JsonArray(v.value.map(::JsonPrimitive))
    }

    /** UTC ISO-8601 with exactly 3 fractional digits, like Dart `toUtc().toIso8601String()` for ms precision. */
    fun isoUtc(ms: Long): String {
        val s = Instant.fromEpochMilliseconds(ms).toString() // e.g. 2026-11-10T12:34:56Z or …56.7Z
        val base = s.removeSuffix("Z")
        val dot = base.indexOf('.')
        val whole = if (dot < 0) base else base.substring(0, dot)
        val frac = ((ms % 1000 + 1000) % 1000).toString().padStart(3, '0')
        return "$whole.${frac}Z"
    }

    // ── Parse and validate ──────────────────────────────────────────────────

    fun parse(content: String): BackupDocument {
        val decoded = try {
            Json.parseToJsonElement(stripBom(content))
        } catch (e: Exception) {
            throw BackupError.InvalidJson(e.message ?: "invalid JSON")
        }
        if (decoded !is JsonObject || str(decoded["format"]) != FORMAT) throw BackupError.NotABackup()
        val version = decoded["version"]
        if (intOrNull(version) != VERSION.toLong()) {
            throw BackupError.UnsupportedVersion(version?.toString() ?: "null")
        }
        val createdAt = parseDate(string(decoded["createdAt"], "createdAt")) ?: malformed("createdAt")

        val songs = LinkedHashMap<Long, BackupSongRef>()
        list(decoded["songs"].orNull() ?: JsonArray(emptyList()), "songs").forEachIndexed { i, raw ->
            val p = "songs[$i]"
            val m = obj(raw, p)
            val ref = BackupSongRef(
                id = int(m["id"], "$p.id"), path = string(m["path"], "$p.path"),
                title = string(m["title"], "$p.title"), artist = string(m["artist"], "$p.artist"),
                album = string(m["album"], "$p.album"), durationMs = int(m["durationMs"], "$p.durationMs"),
            )
            songs[ref.id] = ref
        }

        val settings = LinkedHashMap<String, SettingValue>()
        val rawSettings = decoded["settings"].orNull()
        if (rawSettings != null) {
            for ((key, value) in obj(rawSettings, "settings")) {
                val type = SettingsWhitelist.parseKeys[key] ?: continue
                if (value is JsonNull) continue
                settings[key] = settingValue(value, type, "settings.$key")
            }
        }

        val playlists = decoded["playlists"].orNull()?.let { rp ->
            list(rp, "playlists").mapIndexed { i, raw ->
                val p = "playlists[$i]"
                val m = obj(raw, p)
                val desc = m["description"].orNull()
                if (desc != null && (desc !is JsonPrimitive || !desc.isString)) malformed("$p.description")
                BackupPlaylist(
                    id = string(m["id"], "$p.id"), name = string(m["name"], "$p.name"),
                    description = (desc as JsonPrimitive?)?.content,
                    songIds = list(m["songIds"], "$p.songIds").mapIndexed { j, id -> int(id, "$p.songIds[$j]") },
                )
            }
        }

        var favSongs: Map<Long, Long?>? = null
        var favAlbums: Map<String, Long>? = null
        var favArtists: Map<String, Long>? = null
        decoded["favorites"].orNull()?.let { rf ->
            val f = obj(rf, "favorites")
            val fs = LinkedHashMap<Long, Long?>()
            list(f["songs"].orNull() ?: JsonArray(emptyList()), "favorites.songs").forEachIndexed { i, raw ->
                val p = "favorites.songs[$i]"
                val m = obj(raw, p)
                val date = m["favoriteDateMs"].orNull()
                fs[int(m["id"], "$p.id")] = date?.let { int(it, "$p.favoriteDateMs") }
            }
            favSongs = fs
            favAlbums = intMap(f["albums"].orNull() ?: JsonObject(emptyMap()), "favorites.albums")
            favArtists = intMap(f["artists"].orNull() ?: JsonObject(emptyMap()), "favorites.artists")
        }

        val stats = decoded["stats"].orNull()?.let { rs ->
            val m = obj(rs, "stats")
            val firstDate = m["first_played_date"].orNull()
            if (firstDate != null && (firstDate !is JsonPrimitive || !firstDate.isString)) malformed("stats.first_played_date")
            fun optInt(key: String, fallback: Long) = m[key].orNull()?.let { int(it, "stats.$key") } ?: fallback
            fun idMap(key: String) = idKeyedMap(m[key].orNull(), "stats.$key")
            BackupStats(
                completeSongListens = optInt("complete_song_listens", 0),
                firstPlayedSongId = optInt("first_played_song_id", -1),
                firstPlayedDate = (firstDate as JsonPrimitive?)?.content,
                shuffleSessionStarts = optInt("shuffle_session_starts", 0),
                songPlayCounts = idMap("song_play_counts"),
                playlistPlayCounts = intMap(m["playlist_play_counts"].orNull() ?: JsonObject(emptyMap()), "stats.playlist_play_counts"),
                weeklyPlayCounts = idMap("weekly_play_counts"),
                songSkipCounts = idMap("song_skip_counts"),
                songRestartCounts = idMap("song_restart_counts"),
                songLastPlayedAtMs = idMap("song_last_played_at_ms"),
            )
        }

        return BackupDocument(
            createdAtMs = createdAt,
            appVersion = str(decoded["appVersion"]) ?: "",
            songs = songs, settings = settings, hasSettings = rawSettings != null,
            playlists = playlists, favoriteSongs = favSongs, favoriteAlbums = favAlbums,
            favoriteArtists = favArtists, stats = stats,
        )
    }

    /** Dart `DateTime.tryParse` subset: ISO-8601 with optional offset (none = treated as UTC here). */
    internal fun parseDate(s: String): Long? {
        val t = s.trim().replace(' ', 'T')
        val hasOffset = t.endsWith('Z') || t.endsWith('z') || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(t.substringAfter('T', ""))
        val withTime = if ('T' in t) t else "${t}T00:00:00"
        val normalized = if (hasOffset) withTime.replace('z', 'Z') else "${withTime}Z"
        return runCatching { Instant.parse(normalized).toEpochMilliseconds() }.getOrNull()
    }

    private fun JsonElement?.orNull(): JsonElement? = if (this == null || this is JsonNull) null else this

    private fun malformed(path: String): Nothing = throw BackupError.Malformed(path)

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun intOrNull(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return if (Regex("-?\\d+").matches(p.content)) p.content.toLongOrNull() else null
    }

    private fun obj(e: JsonElement?, path: String): JsonObject = e as? JsonObject ?: malformed(path)
    private fun list(e: JsonElement?, path: String): JsonArray = e as? JsonArray ?: malformed(path)
    private fun string(e: JsonElement?, path: String): String = str(e) ?: malformed(path)
    private fun int(e: JsonElement?, path: String): Long = intOrNull(e) ?: malformed(path)

    private fun intMap(e: JsonElement, path: String): Map<String, Long> =
        obj(e, path).entries.associate { (k, v) -> k to int(v, "$path.$k") }

    private fun idKeyedMap(e: JsonElement?, path: String): Map<Long, Long> {
        if (e == null) return emptyMap()
        return obj(e, path).entries.associate { (k, v) ->
            (k.toLongOrNull() ?: malformed("$path.$k")) to int(v, "$path.$k")
        }
    }

    private fun settingValue(e: JsonElement, type: PrefType, path: String): SettingValue {
        val p = e as? JsonPrimitive
        val isBool = p != null && !p.isString && (p.content == "true" || p.content == "false")
        val isNum = p != null && !p.isString && !isBool && p.content.toDoubleOrNull() != null
        return when (type) {
            PrefType.Boolean -> if (isBool) SettingValue.Bool(p!!.content == "true") else null
            PrefType.Integer -> intOrNull(e)?.let(SettingValue::Int)
            PrefType.Decimal -> if (isNum) SettingValue.Decimal(p!!.content.toDouble()) else null
            PrefType.String -> str(e)?.let(SettingValue::Str)
            PrefType.StringList -> (e as? JsonArray)?.let { arr ->
                val items = arr.map { str(it) ?: return@let null }
                SettingValue.StrList(items)
            }
        } ?: malformed(path)
    }
}
