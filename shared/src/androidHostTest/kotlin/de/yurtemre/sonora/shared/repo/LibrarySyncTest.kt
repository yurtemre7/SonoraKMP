package de.yurtemre.sonora.shared.repo

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import de.yurtemre.sonora.shared.audio.replaygain.ByteArraySource
import de.yurtemre.sonora.shared.db.FavoriteSongEntity
import de.yurtemre.sonora.shared.db.PlaylistEntity
import de.yurtemre.sonora.shared.db.PlaylistEntryEntity
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryScanner
import de.yurtemre.sonora.shared.repo.api.ScanEnrichment
import de.yurtemre.sonora.shared.repo.api.ScanResult
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import de.yurtemre.sonora.shared.repo.impl.LibraryPlatform
import de.yurtemre.sonora.shared.repo.impl.LibraryRepositoryImpl
import de.yurtemre.sonora.shared.repo.impl.LibrarySettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LibrarySyncTest {
    @Test fun idChangeRelinksAndNullScanKeepsCache() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<SonoraDatabase>(LibraryHostContext)
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var next: List<Song>? = listOf(Song(1, "a", "x", "y", 1, "/m/a.mp3"), Song(2, "b", "x", "y", 1, "/m/b.mp3"))
        val scanner = object : LibraryScanner {
            override suspend fun scan(folderPath: String?, folderUri: String?, cached: List<Song>) = next?.let { ScanResult(it, emptyMap()) }
        }
        val settings = object : LibrarySettings {
            var synced: Long? = null
            override suspend fun scanFolder() = null to null
            override suspend fun lastSyncMs() = synced
            override suspend fun postponeUntilMs() = 0L
            override suspend fun colorlessPaths() = emptySet<String>()
            override suspend fun recordSync(timestampMs: Long, durationMs: Long) { synced = timestampMs }
            override suspend fun setPostponeUntil(ms: Long) {}
            override suspend fun addColorless(paths: Set<String>) {}
        }
        val repo = LibraryRepositoryImpl(db, scanner, settings,
            LibraryPlatform({ ByteArraySource(ByteArray(0)) }, { null }), Clock { 5_000 }, scope)
        try {
            assertIs<SyncOutcome.Success>(repo.sync())
            db.favoritesDao().upsertSongs(listOf(FavoriteSongEntity(1, "/m/a.mp3", 1)))
            db.playlistDao().upsertWithEntries(PlaylistEntity("p", "P", createdAtMs = 0, position = 0),
                listOf(PlaylistEntryEntity("p", 0, 2, "/m/b.mp3"), PlaylistEntryEntity("p", 1, 1, "/m/a.mp3")))
            db.statsDao().recordPlay(2, "/m/b.mp3", 1, 1, null)

            next = listOf(Song(11, "a", "x", "y", 1, "/m/a.mp3"), Song(12, "b", "x", "y", 1, "/m/b.mp3"))
            val out = assertIs<SyncOutcome.Success>(repo.sync())
            assertEquals(mapOf(1L to 11L, 2L to 12L), out.idRemap)
            assertEquals(listOf(11L), db.favoritesDao().getSongs().map { it.songId })
            assertEquals(listOf(12L, 11L), db.playlistDao().getEntries("p").map { it.songId })
            assertEquals(listOf(12L), db.statsDao().getAllSongStats().map { it.songId })
            assertEquals(true, repo.songs.first().single { it.id == 11L }.isFavorite)

            next = null
            assertEquals(SyncOutcome.FolderMissing, repo.sync())
            assertEquals(setOf(11L, 12L), repo.songs.first().map { it.id }.toSet())
        } finally {
            scope.cancel(); db.close()
        }
    }

    @Test fun syncReturnsBeforeSlowEnrichmentWhichPatchesRowsLater() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<SonoraDatabase>(LibraryHostContext)
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = CompletableDeferred<Unit>()
        var enrichCalls = 0
        val scanner = object : LibraryScanner {
            override suspend fun scan(folderPath: String?, folderUri: String?, cached: List<Song>) =
                ScanResult(listOf(Song(1, "a", "x", "y", 1, "/m/a.mp3", albumId = 7), Song(2, "b", "x", "y", 1, "/m/b.mp3", albumId = 7)), null)
            override suspend fun enrich(folderPath: String?, folderUri: String?, songs: List<Song>): ScanEnrichment {
                enrichCalls++
                gate.await() // slow work (covers, SAF index, lyrics, artist images) must not block sync()
                return ScanEnrichment(mapOf(7L to "/art/7.jpg"), setOf("/m/a.mp3"), mapOf("x" to "/img/x.jpg"))
            }
        }
        val settings = object : LibrarySettings {
            override suspend fun scanFolder() = "/m" to null
            override suspend fun lastSyncMs() = null
            override suspend fun postponeUntilMs() = 0L
            override suspend fun colorlessPaths() = emptySet<String>()
            override suspend fun recordSync(timestampMs: Long, durationMs: Long) {}
            override suspend fun setPostponeUntil(ms: Long) {}
            override suspend fun addColorless(paths: Set<String>) {}
        }
        val repo = LibraryRepositoryImpl(db, scanner, settings,
            LibraryPlatform({ ByteArraySource(ByteArray(0)) }, { null }), Clock { 5_000 }, scope, seedStartDelayMs = 60_000)
        try {
            val out = withTimeout(5_000) { repo.sync() }
            assertEquals(2, assertIs<SyncOutcome.Success>(out).songCount)
            assertEquals(listOf(null, null), repo.songs.first().map { it.artworkPath })
            gate.complete(Unit)
            val patched = withTimeout(5_000) { repo.songs.first { s -> s.all { it.artworkPath == "/art/7.jpg" } } }
            assertEquals(listOf(true, false), patched.sortedBy { it.id }.map { it.hasLyrics })
            assertEquals(mapOf("x" to "/img/x.jpg"), withTimeout(5_000) { repo.artistImages.first { it.isNotEmpty() } })
            assertEquals(1, enrichCalls)
        } finally {
            scope.cancel(); db.close()
        }
    }
}

private object LibraryHostContext : android.content.ContextWrapper(null) {
    override fun getApplicationContext(): android.content.Context = this
    override fun getSystemService(name: String): Any? = null
}
