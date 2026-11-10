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
import kotlin.test.assertIs
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.yurtemre.sonora.shared.backup.FLUTTER_1_28_1_BACKUP
import de.yurtemre.sonora.shared.repo.api.BackupExportResult
import de.yurtemre.sonora.shared.repo.api.BackupRestoreResult
import de.yurtemre.sonora.shared.repo.impl.BackupRepositoryImpl
import de.yurtemre.sonora.shared.settings.SettingsRepository
import okio.Path.Companion.toOkioPath

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

    private fun newDb() = Room.inMemoryDatabaseBuilder<SonoraDatabase>(HostContext)
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()

    private fun newSettings() = SettingsRepository(PreferenceDataStoreFactory.createWithPath {
        java.io.File.createTempFile("prefs", ".preferences_pb").also { it.delete() }.toOkioPath()
    })

    @Test fun backupExportRestoreRoundTrip() = runBlocking {
        db.songDao().upsertAll(listOf(song(1, "/m/a.mp3"), song(2, "/m/b.mp3"), song(3, "/m/c.mp3")))
        val settings = newSettings()
        settings.setUserName("Emre"); settings.setDefaultThemeColor(0xFF112233); settings.setSleepTimerDefaultMinutes(45)
        val p = playlists.create("Mix", listOf(2, 1), "d")
        favorites.toggleSong(3, "/m/c.mp3"); favorites.toggleAlbum("al|||a")
        stats.recordCompleteListen(1, 5_000, 2, "2026-11-10", p.id)
        val backup = BackupRepositoryImpl(db, settings, stats, files, clock, "1.0+1")
        assertEquals(BackupExportResult.Success, backup.export("content://out"))

        val db2 = newDb()
        try {
            db2.songDao().upsertAll(listOf(song(11, "/m/a.mp3"), song(12, "/m/b.mp3"), song(13, "/m/c.mp3")))
            val settings2 = newSettings(); val stats2 = StatsRepositoryImpl(db2)
            val r = BackupRepositoryImpl(db2, settings2, stats2, files, clock, "1.0+1").restore("content://out")
            assertEquals(BackupRestoreResult.Success(1, 2, 0, false), r)
            val restored = PlaylistRepositoryImpl(db2, files, clock).playlist(p.id).first()!!
            assertEquals(Triple("Mix", "d", listOf(12L, 11)), Triple(restored.name, restored.description, restored.songIds))
            val fav2 = FavoritesRepositoryImpl(db2, clock)
            assertEquals(setOf(13L), fav2.favoriteSongs.first().keys)
            assertEquals(setOf("al|||a"), fav2.favoriteAlbums.first().keys)
            val s = stats2.snapshot.first()
            assertEquals(1, s.songs.getValue(11).plays)
            assertEquals(Triple(11L, "2026-11-10", mapOf(p.id to 1)), Triple(s.firstPlayedSongId, s.firstPlayedDate, s.playlistPlays))
            val a = settings2.settings.first()
            assertEquals(Triple("Emre", 0xFF112233, 45), Triple(a.userName, a.defaultThemeColor, a.sleepTimerDefaultMinutes))
        } finally { db2.close() }
    }

    @Test fun backupRestoresFlutterFixture() = runBlocking {
        db.songDao().upsertAll(listOf(
            SongEntity(1, "One More Time", "Daft Punk", "Discovery", 320_000, "/sdcard/Music/Daft Punk/Discovery/01 One More Time.mp3"),
            SongEntity(2, "First Love", "宇多田ヒカル", "First Love", 259_733, "/storage/emulated/0/Music/宇多田ヒカル/First Love/03 First Love.flac"),
        ))
        files.store["content://flutter"] = FLUTTER_1_28_1_BACKUP
        val settings = newSettings()
        val r = BackupRepositoryImpl(db, settings, stats, files, clock, "1.0+1").restore("content://flutter")
        assertIs<BackupRestoreResult.Success>(r)
        assertEquals(2, r.playlists)
        assertEquals(true, r.localeChanged)
        val all = playlists.playlists.first()
        assertEquals(listOf("Late Night" to listOf(1L, 2L), "Empty" to emptyList()), all.map { it.name to it.songIds })
        assertEquals(setOf(2L), favorites.favoriteSongs.first().keys)
        assertEquals(31, stats.snapshot.first().songs.getValue(1).plays)
        assertEquals("ja", settings.settings.first().appLocale.storageName)
    }
}

/** Room's Android builder needs a Context; host android.jar stubs throw, so answer the few calls it makes. */
private object HostContext : android.content.ContextWrapper(null) {
    override fun getApplicationContext(): android.content.Context = this
    override fun getSystemService(name: String): Any? = null
}
