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



    @Test fun favoritesRelinkByPath() = runBlocking {
        favorites.toggleSong(1, "/m/a.mp3")
        favorites.setSongsFavorite(mapOf(2L to "/m/b.mp3"), true)
        favorites.relinkByPath(mapOf("/m/a.mp3" to 10L, "/m/b.mp3" to 2L))
        assertEquals(setOf(10L, 2L), favorites.favoriteSongs.first().keys)
        favorites.toggleAlbum("x|||y"); favorites.toggleAlbum("x|||y"); favorites.toggleArtist("z")
        assertEquals(emptyMap(), favorites.favoriteAlbums.first())
        assertEquals(setOf("z"), favorites.favoriteArtists.first().keys)
    }


    private fun newDb() = Room.inMemoryDatabaseBuilder<SonoraDatabase>(HostContext)
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()

    private fun newSettings() = SettingsRepository(PreferenceDataStoreFactory.createWithPath {
        java.io.File.createTempFile("prefs", ".preferences_pb").also { it.delete() }.toOkioPath()
    })


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
