package de.yurtemre.sonora.shared.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import de.yurtemre.sonora.shared.backup.BackupCodec
import de.yurtemre.sonora.shared.db.SongEntity
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.devicesync.SyncBundle
import de.yurtemre.sonora.shared.devicesync.SyncStats
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.DeviceSyncApplyResult
import de.yurtemre.sonora.shared.repo.api.DeviceSyncDataException
import de.yurtemre.sonora.shared.repo.api.FileStore
import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import de.yurtemre.sonora.shared.repo.impl.DeviceSyncDataRepositoryImpl
import de.yurtemre.sonora.shared.repo.impl.FavoritesRepositoryImpl
import de.yurtemre.sonora.shared.repo.impl.PlaylistRepositoryImpl
import de.yurtemre.sonora.shared.repo.impl.StatsRepositoryImpl
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceSyncDataTest {
    private class MemFiles : FileStore {
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

    private inner class Device {
        val db = Room.inMemoryDatabaseBuilder<SonoraDatabase>(SyncHostContext)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        val settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath {
            java.io.File.createTempFile("prefs", ".preferences_pb").also { it.delete() }.toOkioPath()
        })
        val files = MemFiles()
        val stats = StatsRepositoryImpl(db)
        val playlists = PlaylistRepositoryImpl(db, files, clock)
        val favorites = FavoritesRepositoryImpl(db, clock)
        val repo = DeviceSyncDataRepositoryImpl(db, settings, stats, files, clock, "1.0+1")

        suspend fun prefs(): Map<String, Any> {
            var out: Map<String, Any> = emptyMap()
            settings.edit { m -> out = m.asMap().mapKeys { it.key.name } }
            return out
        }

        /** Everything a bundle can touch, ordered, for idempotency checks. */
        suspend fun dump(): String = buildString {
            appendLine(db.playlistDao().getPlaylists().sortedBy { it.id })
            appendLine(db.playlistDao().observeEntries().first().sortedWith(compareBy({ it.playlistId }, { it.position })))
            appendLine(db.favoritesDao().getSongs().sortedBy { it.songId })
            appendLine(db.favoritesDao().observeAlbums().first().sortedBy { it.key })
            appendLine(db.favoritesDao().observeArtists().first().sortedBy { it.key })
            appendLine(db.statsDao().getAllSongStats().sortedBy { it.songId })
            appendLine(db.statsDao().observeWeekdayStats().first())
            appendLine(db.statsDao().observePlaylistStats().first().sortedBy { it.playlistId })
            appendLine(db.statsDao().getSummary())
            appendLine(prefs().toSortedMap())
        }

        fun close() = db.close()
    }

    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private val sender = Device()
    private val receiver = Device()

    @AfterTest fun close() { sender.close(); receiver.close() }

    private fun song(id: Long, path: String, title: String) = SongEntity(id, title, "Artist", "Album", 200_000, path)

    private suspend fun seedSender() {
        sender.db.songDao().upsertAll(listOf(
            song(1, "/sdcard/Music/A/One.mp3", "One"),
            song(2, "/sdcard/Music/A/Two.mp3", "Two"),
            song(3, "/sdcard/Music/A/Three.mp3", "Three"),
        ))
        sender.playlists.create("Mix", listOf(2, 1))
        sender.favorites.toggleSong(1, "/sdcard/Music/A/One.mp3")
        sender.favorites.toggleAlbum("Album|||Artist")
        repeat(2) { sender.stats.recordCompleteListen(3, now, 2, "2026-11-01", null) }
        sender.stats.recordSkip(2)
        sender.settings.edit {
            it[doublePreferencesKey("volume")] = 0.3
            it[stringPreferencesKey("user_name")] = "Emre"
            it[stringPreferencesKey("scan_folder_path")] = "/sdcard/Music"
            it[stringPreferencesKey("repeat_mode")] = "all"
        }
    }

    /** Receiver has the same songs under other ids and another folder (tag match). */
    private suspend fun seedReceiver() {
        receiver.db.songDao().upsertAll(listOf(
            song(11, "/storage/emulated/0/Music/One.flac", "One"),
            song(12, "/storage/emulated/0/Music/Two.mp3", "Two"),
            song(13, "/storage/emulated/0/Music/Three.mp3", "Three"),
        ))
        receiver.stats.recordCompleteListen(13, now - 10, 1, "2026-10-01", null)
        receiver.settings.edit { it[doublePreferencesKey("volume")] = 0.5 }
    }

    @Test fun exportOmitsExcludedKeysAndStatsSection() = runBlocking {
        seedSender()
        val bundle = sender.repo.exportBundle()
        val doc = BackupCodec.parse(bundle.backupJson)
        assertEquals(setOf("user_name"), doc.settings.keys.filter { it in setOf("user_name", "volume", "repeat_mode", "scan_folder_path") }.toSet())
        assertTrue(doc.stats!!.songPlayCounts.isEmpty() && doc.stats.completeSongListens == 0L)
        // Song refs cover the stats songs although stats are omitted from the JSON.
        assertEquals(setOf(1L, 2L, 3L), doc.songs.keys)
        assertEquals(2, bundle.stats.songs.getValue("/sdcard/Music/A/Three.mp3").plays)
        assertEquals(1, bundle.stats.songs.getValue("/sdcard/Music/A/Two.mp3").skips)
        assertEquals(2, bundle.stats.completeListens)
        assertEquals("/sdcard/Music/A/Three.mp3", bundle.stats.firstPlayedPath)
    }

    @Test fun applyTwiceGivesIdenticalDb() = runBlocking {
        seedSender(); seedReceiver()
        val bundle = sender.repo.exportBundle()
        val r = receiver.repo.applyBundle(bundle, "sender-1")
        assertEquals(DeviceSyncApplyResult(playlists = 1, favorites = 2, settings = r.settings, statsSongsAdded = 2,
            completeListensAdded = 2, localeChanged = false), r)
        val first = receiver.dump()
        receiver.repo.applyBundle(bundle, "sender-1")
        assertEquals(first, receiver.dump())

        assertEquals(listOf(12L, 11L), receiver.playlists.playlists.first().single().songIds)
        assertEquals(setOf(11L), receiver.favorites.favoriteSongs.first().keys)
        assertEquals(setOf("Album|||Artist"), receiver.favorites.favoriteAlbums.first().keys)
        val s = receiver.stats.snapshot.first()
        assertEquals(3, s.songs.getValue(13).plays)
        assertEquals(1, s.songs.getValue(12).skips)
        assertEquals(3, s.completeListens)
        assertEquals("2026-10-01", s.firstPlayedDate) // receiver's own first play is earlier
        assertEquals("Emre", receiver.prefs()["user_name"])
        assertEquals(0.5, receiver.prefs()["volume"])
        assertFalse("scan_folder_path" in receiver.prefs())
        assertFalse("repeat_mode" in receiver.prefs())
    }

    @Test fun threeNewPlaysOnSenderAddExactlyThree() = runBlocking {
        seedSender(); seedReceiver()
        receiver.repo.applyBundle(sender.repo.exportBundle(), "sender-1")
        val before = receiver.stats.snapshot.first()
        repeat(3) { sender.stats.recordCompleteListen(1, now + 1, 3, "2026-11-02", null) }
        val r = receiver.repo.applyBundle(sender.repo.exportBundle(), "sender-1")
        val after = receiver.stats.snapshot.first()
        assertEquals((before.songs[11]?.plays ?: 0) + 3, after.songs.getValue(11).plays)
        assertEquals(before.songs.getValue(13).plays, after.songs.getValue(13).plays)
        assertEquals(before.completeListens + 3, after.completeListens)
        assertEquals((before.weekdayCounts[3] ?: 0) + 3, after.weekdayCounts.getValue(3))
        assertEquals(1, r.statsSongsAdded)
        assertEquals(3, r.completeListensAdded)
    }

    @Test fun playsOfASongThatMatchesOnlyLaterArriveExactlyOnce() = runBlocking {
        seedSender()
        // First device sync: "Three" was deselected, so the receiver has no song for it yet.
        receiver.db.songDao().upsertAll(listOf(
            song(11, "/storage/emulated/0/Music/One.flac", "One"),
            song(12, "/storage/emulated/0/Music/Two.mp3", "Two"),
        ))
        receiver.repo.applyBundle(sender.repo.exportBundle(), "sender-1")
        assertEquals(null, receiver.stats.snapshot.first().songs[13])

        // "Three" arrives later (a second device sync copied it and the library was rescanned).
        receiver.db.songDao().upsertAll(listOf(song(13, "/storage/emulated/0/Music/Three.mp3", "Three")))
        val r = receiver.repo.applyBundle(sender.repo.exportBundle(), "sender-1")
        assertEquals(2, receiver.stats.snapshot.first().songs.getValue(13).plays)
        assertEquals(1, r.statsSongsAdded)
        assertEquals(0, r.completeListensAdded) // the aggregate counters were imported by the first sync

        // Repeating the sync adds nothing.
        val before = receiver.dump()
        receiver.repo.applyBundle(sender.repo.exportBundle(), "sender-1")
        assertEquals(before, receiver.dump())
        assertEquals(2, receiver.stats.snapshot.first().songs.getValue(13).plays)
    }

    @Test fun baselineFileWrittenWithReceivedStats() = runBlocking {
        seedSender(); seedReceiver()
        val bundle = sender.repo.exportBundle()
        receiver.repo.applyBundle(bundle, "0f8fad5b-d9cb-469f-a165-70867728950e")
        val path = "/files/device_sync/peers/0f8fad5b-d9cb-469f-a165-70867728950e.json"
        assertEquals(bundle.stats, Json.decodeFromString(SyncStats.serializer(), receiver.files.readText(path)))
    }

    @Test fun excludedKeysNeverAppliedFromCraftedBundle() = runBlocking {
        seedReceiver()
        val json = """{"format":"sonora-backup","version":1,"createdAt":"2026-11-15T00:00:00.000Z",
            "songs":[],"settings":{"volume":0.9,"user_name":"X","speed":1.25}}"""
        val r = receiver.repo.applyBundle(SyncBundle(json, SyncStats()), "sender-1")
        assertEquals(0.5, receiver.prefs()["volume"])
        assertEquals("X", receiver.prefs()["user_name"])
        assertEquals(1.25, receiver.prefs()["speed"])
        assertEquals(2, r.settings)
    }

    @Test fun invalidSenderDeviceIdRejected() = runBlocking {
        seedSender(); seedReceiver()
        val bundle = sender.repo.exportBundle()
        for (bad in listOf("../evil", "", "a/b", "x".repeat(65), "id.json")) {
            assertFailsWith<DeviceSyncDataException.InvalidPeerId> { receiver.repo.applyBundle(bundle, bad) }
        }
        assertTrue(receiver.files.store.keys.none { "peers" in it })
        assertEquals(0.5, receiver.prefs()["volume"])
        assertFalse("user_name" in receiver.prefs())
    }

    @Test fun invalidBundleRejected() = runBlocking {
        seedReceiver()
        assertFailsWith<DeviceSyncDataException.InvalidBundle> {
            receiver.repo.applyBundle(SyncBundle("{nope", SyncStats()), "sender-1")
        }
        Unit
    }

    @Test fun emptyReceiverLibraryAppliesSettingsOnly() = runBlocking {
        seedSender()
        val r = receiver.repo.applyBundle(sender.repo.exportBundle(), "sender-1")
        assertEquals(DeviceSyncApplyResult(0, 0, r.settings, 0, 0, false), r)
        assertTrue(r.settings > 0)
        assertEquals("Emre", receiver.prefs()["user_name"])
        assertTrue(receiver.playlists.playlists.first().isEmpty())
        assertEquals(StatsSnapshot(), receiver.stats.snapshot.first())
        assertTrue(receiver.files.store.keys.none { "peers" in it }) // stats deferred to the next sync
    }

    @Test fun noMatchingSongsIsNotAnError() = runBlocking {
        seedSender()
        receiver.db.songDao().upsertAll(listOf(song(50, "/storage/Music/Other.mp3", "Other")))
        val r = receiver.repo.applyBundle(sender.repo.exportBundle(), "sender-1")
        assertEquals(0, r.statsSongsAdded)
        assertEquals(2, r.completeListensAdded)
        assertEquals("Emre", receiver.prefs()["user_name"])
    }

    @Test fun deviceIdIsCreatedOnceAndPersisted() = runBlocking {
        val id = sender.repo.deviceId()
        assertTrue(Regex("[0-9a-f-]{36}").matches(id))
        assertEquals(id, sender.repo.deviceId())
        assertEquals(id, sender.files.readText("/files/device_sync/device_id").trim())
        val again = DeviceSyncDataRepositoryImpl(sender.db, sender.settings, sender.stats, sender.files, clock, "1.0+1")
        assertEquals(id, again.deviceId())
        assertNotEquals(id, receiver.repo.deviceId())
    }

    @Test fun relinkReplacedMovesFavoritesPlaylistEntriesAndStats() = runBlocking {
        val db = receiver.db
        db.songDao().upsertAll(listOf(song(1, "/m/Song.mp3", "Song"), song(2, "/m/Song.flac", "Song"), song(3, "/m/Other.mp3", "Other")))
        receiver.favorites.toggleSong(1, "/m/Song.mp3")
        val p = receiver.playlists.create("P", listOf(3, 1))
        receiver.stats.recordCompleteListen(1, now, 0, "2026-01-01", null)
        receiver.stats.recordRecent(1, now)
        receiver.stats.recordSkip(1)
        receiver.stats.recordSkip(2)

        receiver.repo.relinkReplaced(mapOf(1L to 2L))

        assertEquals(setOf(2L), receiver.favorites.favoriteSongs.first().keys)
        assertEquals(listOf("/m/Song.flac"), db.favoritesDao().getSongs().map { it.filePath })
        assertEquals(listOf(3L, 2L), receiver.playlists.playlist(p.id).first()!!.songIds)
        assertEquals("/m/Song.flac", db.playlistDao().getEntries(p.id)[1].filePath)
        val s = receiver.stats.snapshot.first()
        assertEquals(setOf(2L), s.songs.keys)
        assertEquals(SongStats(plays = 1, skips = 2, restarts = 0, lastPlayedAtMs = now), s.songs.getValue(2))
        assertEquals(2L, s.firstPlayedSongId)
        assertEquals("/m/Song.flac", db.statsDao().getSongStats(2)!!.filePath)
    }
}

/** Room's Android builder needs a Context; host android.jar stubs throw, so answer the few calls it makes. */
private object SyncHostContext : android.content.ContextWrapper(null) {
    override fun getApplicationContext(): android.content.Context = this
    override fun getSystemService(name: String): Any? = null
}
