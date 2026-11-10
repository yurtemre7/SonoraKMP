package de.yurtemre.sonora.shared.repo

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import de.yurtemre.sonora.shared.db.SongEntity
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.FileStore
import de.yurtemre.sonora.shared.repo.impl.FavoritesRepositoryImpl
import de.yurtemre.sonora.shared.repo.impl.PlaylistRepositoryImpl
import de.yurtemre.sonora.shared.repo.impl.StatsRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoriesTest {
    private val db = Room.inMemoryDatabaseBuilder<SonoraDatabase>(HostContext)
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private var now = 1_000L
    private val clock = Clock { now }
    private val files = object : FileStore {
        val store = mutableMapOf<String, Any>()
        override fun filesPath(relative: String) = "/files/$relative"
        override fun cachePath(relative: String) = "/cache/$relative"
        override suspend fun exists(path: String) = path in store
        override suspend fun readText(path: String) = store.getValue(path) as String
        override suspend fun writeText(path: String, text: String) { store[path] = text }
        override suspend fun writeBytes(path: String, bytes: ByteArray) { store[path] = bytes }
        override suspend fun delete(path: String) = store.remove(path) != null
        override suspend fun readUriBytes(uri: String) = (store.getValue(uri) as String).encodeToByteArray()
        override suspend fun writeUriText(uri: String, text: String) { store[uri] = text }
    }
    private val playlists = PlaylistRepositoryImpl(db, files, clock)
    private val favorites = FavoritesRepositoryImpl(db, clock)
    private val stats = StatsRepositoryImpl(db)

    @AfterTest fun close() = db.close()

    private fun song(id: Long, path: String) = SongEntity(id, "t$id", "a", "al", 60_000, path)

    @Test fun playlistAddDedupesRemoveAllAndReorder() = runBlocking {
        val p = playlists.create("P", listOf(1, 2))
        playlists.addSongs(p.id, listOf(2, 3, 3))
        assertEquals(listOf(1L, 2, 3), playlists.playlist(p.id).first()!!.songIds)
        playlists.createFromQueue("Q", listOf(5, 5)).let { assertEquals(listOf(5L, 5), playlists.playlist(it.id).first()!!.songIds) }
        playlists.reorder(p.id, listOf(3, 1, 2, 1))
        playlists.removeSongs(p.id, listOf(1))
        assertEquals(listOf(3L, 2), playlists.playlist(p.id).first()!!.songIds)
    }

    @Test fun playlistIdCollisionAndCoverReplace() = runBlocking {
        val a = playlists.create("A"); val b = playlists.create("B")
        assertEquals(listOf("1000", "1001"), listOf(a.id, b.id))
        playlists.setCover(a.id, byteArrayOf(1))
        val first = playlists.playlist(a.id).first()!!.coverPath!!
        now = 2_000
        playlists.setCover(a.id, byteArrayOf(2))
        assertEquals(false, files.exists(first))
        playlists.setCover(a.id, null)
        assertNull(playlists.playlist(a.id).first()!!.coverPath)
        assertEquals(emptyMap(), files.store)
    }

    @Test fun m3uRoundTrip() = runBlocking {
        db.songDao().upsertAll(listOf(song(1, "/m/a.mp3"), song(2, "/m/b.mp3")))
        val p = playlists.create("x/y", listOf(2, 1, 99))
        val path = playlists.exportM3u(p.id)
        assertEquals("/cache/share/x_y.m3u", path)
        files.store["content://in"] = files.readText(path)
        val imported = playlists.importM3u("content://in", "mix.m3u8", "desc")!!
        assertEquals("mix" to listOf(2L, 1), imported.name to imported.songIds)
    }

    @Test fun favoritesRelinkByPath() = runBlocking {
        favorites.toggleSong(1, "/m/a.mp3")
        favorites.setSongsFavorite(mapOf(2L to "/m/b.mp3"), true)
        favorites.relinkByPath(mapOf("/m/a.mp3" to 10L, "/m/b.mp3" to 2L))
        assertEquals(setOf(10L, 2L), favorites.favoriteSongs.first().keys)
        favorites.toggleAlbum("x|||y"); favorites.toggleAlbum("x|||y"); favorites.toggleArtist("z")
        assertEquals(emptyMap(), favorites.favoriteAlbums.first())
        assertEquals(setOf("z"), favorites.favoriteArtists.first().keys)
    }

    @Test fun statsRecordPauseRelinkReset() = runBlocking {
        stats.recordCompleteListen(1, 5_000, 3, "2026-11-10", "p1")
        stats.recordSkip(1); stats.recordShuffleSession()
        stats.pauseRecording(); stats.pauseRecording(); stats.resumeRecording()
        stats.recordSkip(1)
        stats.resumeRecording()
        stats.recordRecent(2, 9_000)
        stats.relinkSongIds(mapOf(1L to 2L))
        val s = stats.snapshot.first()
        assertEquals(1, s.songs.getValue(2).plays)
        assertEquals(1, s.songs.getValue(2).skips)
        assertEquals(9_000L, s.songs.getValue(2).lastPlayedAtMs)
        assertEquals(mapOf(3 to 1), s.weekdayCounts)
        assertEquals(Triple(1, 1, "2026-11-10"), Triple(s.completeListens, s.shuffleSessions, s.firstPlayedDate))
        assertEquals(2L, s.firstPlayedSongId)
        stats.reset()
        assertEquals(de.yurtemre.sonora.shared.repo.api.StatsSnapshot(), stats.snapshot.first())
    }
}

/** Room's Android builder needs a Context; host android.jar stubs throw, so answer the few calls it makes. */
private object HostContext : android.content.ContextWrapper(null) {
    override fun getApplicationContext(): android.content.Context = this
    override fun getSystemService(name: String): Any? = null
}
