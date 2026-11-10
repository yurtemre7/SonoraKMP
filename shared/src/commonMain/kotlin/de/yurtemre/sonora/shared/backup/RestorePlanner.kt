package de.yurtemre.sonora.shared.backup

import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot

/** Current device state the planner reads (nothing is written while planning). */
data class RestoreState(
    val library: List<Song>,
    val playlists: List<Playlist>,
    val favoriteAlbums: Map<String, Long>,
    val favoriteArtists: Map<String, Long>,
    /** Stored KMP settings (see [SettingsWhitelist]); only consulted for keys the plan touches. */
    val settings: Map<String, SettingValue>,
)

/** Summary counts of a restore (BAK-06). */
data class RestoreCounts(
    val playlistsRestored: Int,
    /** Backup favorite songs that matched the library. */
    val favoriteSongsRestored: Int,
    /** Favorite album + artist entries in the backup. */
    val favoriteAlbumsAndArtistsRestored: Int,
    val unmatchedSongs: Int,
    val settingsRestored: Int,
    val statsRestored: Boolean,
    val localeChanged: Boolean,
)

/** Everything a restore writes; null / empty fields mean "leave as is" (BAK-04). */
data class RestorePlan(
    val idMap: Map<Long, Long>,
    /** Stored KMP key → value to write. */
    val settingWrites: Map<String, SettingValue>,
    /** Stored KMP keys to remove. */
    val settingRemovals: Set<String>,
    val favoriteAlbums: Map<String, Long>?,
    val favoriteArtists: Map<String, Long>?,
    /** Full new playlist list, or null when playlists are untouched. */
    val playlists: List<Playlist>?,
    /** Library songs to newly mark favorite: id → favorite date. Never un-favorites. */
    val newFavoriteSongs: Map<Long, Long>,
    /** Replaces the stats entirely when non-null. */
    val stats: StatsSnapshot?,
    val counts: RestoreCounts,
) {
    val isNoOp: Boolean
        get() = settingWrites.isEmpty() && settingRemovals.isEmpty() && favoriteAlbums == null &&
            favoriteArtists == null && playlists == null && newFavoriteSongs.isEmpty() && stats == null
}

/** Pure restore planner (BAK-04, Flutter `BackupService._restore` plan phase, audit/data §12.5). */
object RestorePlanner {
    fun plan(doc: BackupDocument, state: RestoreState, nowMs: Long): RestorePlan {
        val library = state.library
        if (library.isEmpty()) throw BackupError.LibraryEmpty()
        val idMap = SongMatcher.match(doc.songs.values, library)
        val referenced = doc.referencedSongIds
        if (referenced.isNotEmpty() && referenced.none { it in idMap }) throw BackupError.NoMatchingSongs()
        val unmatched = referenced.count { it !in idMap }

        // Settings: overwrite; drop effect keys absent from a present settings section.
        val writes = SettingsWhitelist.fromBackup(doc.settings)
        val removals = if (doc.hasSettings) {
            SettingsWhitelist.removedWhenAbsent.filter { it !in writes && it in state.settings }.toSet()
        } else emptySet()
        val localeChanged = writes[SettingsWhitelist.APP_LOCALE]
            ?.let { it != (state.settings[SettingsWhitelist.APP_LOCALE] ?: SettingValue.Str("system")) } ?: false

        // Favorite albums / artists: {...backup, ...current}; existing entries keep their dates.
        fun mergeGroups(backup: Map<String, Long>?, current: Map<String, Long>): Map<String, Long>? {
            if (backup == null) return null
            val merged = LinkedHashMap(backup).apply { putAll(current) }
            return merged.takeIf { it != current }
        }
        val groupsRestored = (doc.favoriteAlbums?.size ?: 0) + (doc.favoriteArtists?.size ?: 0)

        // Playlists: merge by id, backup wins, existing cover kept, new ones appended.
        var playlistsAfter: List<Playlist>? = null
        var playlistsRestored = 0
        val backupPlaylists = doc.playlists
        if (!backupPlaylists.isNullOrEmpty()) {
            val after = state.playlists.toMutableList()
            val indexById = HashMap<String, Int>().apply { after.forEachIndexed { i, p -> put(p.id, i) } }
            for (b in backupPlaylists) {
                val existing = indexById[b.id]
                val restored = Playlist(
                    id = b.id, name = b.name, songIds = remapIds(b.songIds, idMap),
                    coverPath = existing?.let { after[it].coverPath }, description = b.description,
                )
                if (existing == null) { indexById[b.id] = after.size; after.add(restored) } else after[existing] = restored
                playlistsRestored++
            }
            playlistsAfter = after.takeIf { it != state.playlists }
        }

        // Favorite songs: union, backup date (or now) for newly favorited songs.
        val favoriteDates = LinkedHashMap<Long, Long?>()
        doc.favoriteSongs?.forEach { (id, date) -> idMap[id]?.let { favoriteDates[it] = date } }
        val newFavorites = library.filter { !it.isFavorite && it.id in favoriteDates }
            .associate { it.id to (favoriteDates[it.id] ?: nowMs) }

        val stats = doc.stats?.remap(idMap)?.toSnapshot()

        return RestorePlan(
            idMap = idMap,
            settingWrites = writes,
            settingRemovals = removals,
            favoriteAlbums = mergeGroups(doc.favoriteAlbums, state.favoriteAlbums),
            favoriteArtists = mergeGroups(doc.favoriteArtists, state.favoriteArtists),
            playlists = playlistsAfter,
            newFavoriteSongs = newFavorites,
            stats = stats,
            counts = RestoreCounts(
                playlistsRestored = playlistsRestored,
                favoriteSongsRestored = favoriteDates.size,
                favoriteAlbumsAndArtistsRestored = groupsRestored,
                unmatchedSongs = unmatched,
                settingsRestored = doc.settings.size,
                statsRestored = stats != null,
                localeChanged = localeChanged,
            ),
        )
    }

    /** Remaps and dedups, dropping unmatched ids. */
    internal fun remapIds(ids: List<Long>, idMap: Map<Long, Long>): List<Long> {
        val seen = HashSet<Long>()
        return ids.mapNotNull { idMap[it] }.filter { seen.add(it) }
    }
}
