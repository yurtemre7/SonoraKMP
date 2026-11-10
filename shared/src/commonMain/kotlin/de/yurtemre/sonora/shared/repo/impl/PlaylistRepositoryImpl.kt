package de.yurtemre.sonora.shared.repo.impl

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import de.yurtemre.sonora.shared.db.PlaylistEntity
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.db.buildPlaylists
import de.yurtemre.sonora.shared.db.toEntity
import de.yurtemre.sonora.shared.db.toEntryEntities
import de.yurtemre.sonora.shared.db.toModel
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.playlist.M3uCodec
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.FileStore
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** LIB-16..19 over Room; covers and M3U files via [FileStore]. */
class PlaylistRepositoryImpl(
    private val db: SonoraDatabase,
    private val files: FileStore,
    private val clock: Clock,
) : PlaylistRepository {
    private val dao = db.playlistDao()
    private val songs = db.songDao()

    override val playlists: Flow<List<Playlist>> =
        combine(dao.observePlaylists(), dao.observeEntries()) { p, e -> buildPlaylists(p, e) }.distinctUntilChanged()

    override fun playlist(id: String): Flow<Playlist?> = playlists.map { l -> l.firstOrNull { it.id == id } }.distinctUntilChanged()

    private suspend inline fun <T> tx(crossinline block: suspend () -> T): T =
        db.useWriterConnection { c -> c.immediateTransaction { block() } }

    private suspend fun pathOf(id: Long): String? = songs.getById(id)?.filePath

    private suspend fun writeEntries(id: String, songIds: List<Long>) {
        val paths = songIds.distinct().associateWith { pathOf(it) }
        dao.setEntries(id, songIds) { paths[it] }
    }

    override suspend fun create(name: String, songIds: List<Long>, description: String?): Playlist =
        insertNew(name, songIds.distinct(), description)

    override suspend fun createFromQueue(name: String, songIds: List<Long>): Playlist = insertNew(name, songIds, null)

    private suspend fun insertNew(name: String, songIds: List<Long>, description: String?): Playlist = tx {
        val now = clock.nowMs()
        var id = now
        while (dao.getPlaylist(id.toString()) != null) id++ // D-24
        val p = Playlist(id.toString(), name, songIds, null, description)
        dao.insertPlaylist(p.toEntity(now, dao.maxPosition() + 1))
        writeEntries(p.id, songIds)
        p
    }

    override suspend fun delete(id: String) {
        val cover = dao.getPlaylist(id)?.coverPath
        tx { dao.deleteEntries(id); dao.deletePlaylist(id) }
        cover?.let { files.delete(it) }
    }

    override suspend fun rename(id: String, name: String) = dao.rename(id, name)
    override suspend fun setDescription(id: String, description: String?) = dao.updateDescription(id, description)

    override suspend fun setCover(id: String, jpegBytes: ByteArray?) {
        val playlist = dao.getPlaylist(id) ?: return
        playlist.coverPath?.let { files.delete(it) }
        if (jpegBytes == null) { dao.updateCover(id, null); return }
        // New name per write so image caches keyed by path invalidate (LIB-17).
        val path = files.filesPath("playlist_covers/${id}_${clock.nowMs()}.jpg")
        files.writeBytes(path, jpegBytes)
        dao.updateCover(id, path)
    }

    override suspend fun addSongs(id: String, songIds: List<Long>) = tx {
        val current = dao.getEntries(id).map { it.songId }
        val seen = current.toHashSet()
        val added = songIds.filter { seen.add(it) }
        if (added.isNotEmpty()) writeEntries(id, current + added)
    }

    override suspend fun removeSongs(id: String, songIds: List<Long>) = tx {
        val drop = songIds.toSet()
        writeEntries(id, dao.getEntries(id).map { it.songId }.filterNot { it in drop })
    }

    override suspend fun reorder(id: String, songIds: List<Long>) = tx { writeEntries(id, songIds) }

    override suspend fun exportM3u(id: String): String {
        val p = dao.getPlaylist(id) ?: error("No playlist $id")
        val list = dao.getEntries(id).mapNotNull { songs.getById(it.songId)?.toModel() }
        val path = files.cachePath("share/" + M3uCodec.exportFileName(p.name))
        files.writeText(path, M3uCodec.encode(list))
        return path
    }

    override suspend fun importM3u(uri: String, fileName: String, description: String): Playlist? {
        if (!M3uCodec.isM3uFile(fileName)) return null
        val text = files.readUriBytes(uri).decodeToString()
        val matched = M3uCodec.match(M3uCodec.decodePaths(text), songs.getAll().map { it.toModel() })
        if (matched.isEmpty()) return null
        return insertNew(M3uCodec.playlistNameFromFile(fileName), matched.map { it.id }, description)
    }

    override suspend fun relinkSongIds(idRemap: Map<Long, Long>) {
        if (idRemap.isEmpty()) return
        tx {
            for (p in dao.getPlaylists()) {
                val ids = dao.getEntries(p.id).map { it.songId }
                if (ids.any { it in idRemap }) writeEntries(p.id, ids.map { idRemap[it] ?: it })
            }
        }
    }

    override suspend fun upsertAll(playlists: List<Playlist>) = tx {
        var pos = dao.maxPosition()
        for (p in playlists) {
            val existing: PlaylistEntity? = dao.getPlaylist(p.id)
            val entity = p.toEntity(existing?.createdAtMs ?: (p.id.toLongOrNull() ?: clock.nowMs()), existing?.position ?: ++pos)
            val paths = p.songIds.distinct().associateWith { pathOf(it) }
            dao.upsertWithEntries(entity, p.toEntryEntities { paths[it] })
        }
    }

    override suspend fun clearAll() {
        val covers = dao.getPlaylists().mapNotNull { it.coverPath }
        dao.clearAll()
        covers.forEach { files.delete(it) }
    }
}
