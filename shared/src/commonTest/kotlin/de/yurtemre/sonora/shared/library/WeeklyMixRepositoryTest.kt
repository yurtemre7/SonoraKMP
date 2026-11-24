package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.BackgroundWork
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeeklyMixRepositoryTest {
    private val zone = TimeZone.UTC

    // 2024-01-01 (Monday) 00:00 UTC = 1_704_067_200_000; 08:00 UTC = 1_704_096_000_000.
    private val mon0800 = 1_704_096_000_000L
    private val week = 7 * 24 * 3_600_000L

    private class FakeStore : WeeklyMixStore {
        val state = MutableStateFlow<StoredWeeklyMix?>(null)
        override val stored: Flow<StoredWeeklyMix?> get() = state
        override suspend fun save(mix: StoredWeeklyMix) { state.value = mix }
    }

    private class FakeLibrary(initial: List<Song>) : LibraryRepository {
        val list = MutableStateFlow(initial)
        override val songs: Flow<List<Song>> get() = list
        override val artistImages: Flow<Map<String, String>> = MutableStateFlow(emptyMap())
        override val isSyncing: Flow<Boolean> = MutableStateFlow(false)
        override val backgroundWork: StateFlow<BackgroundWork?> = MutableStateFlow(null)
        override suspend fun getSong(id: Long): Song? = TODO()
        override suspend fun getSongByPath(filePath: String): Song? = TODO()
        override suspend fun sync(): SyncOutcome = TODO()
        override suspend fun updateArtwork(artworkBySongId: Map<Long, String?>) = TODO()
        override suspend fun updateSeedColor(songId: Long, seedColor: Int?) = TODO()
        override suspend fun updateReplayGain(songs: List<Song>) = TODO()
        override suspend fun updateTags(songId: Long, edit: TagEdit) = TODO()
        override suspend fun clearAll() = TODO()
    }

    private class FakeStats : StatsRepository {
        val snap = MutableStateFlow(StatsSnapshot())
        override val snapshot: Flow<StatsSnapshot> get() = snap
        override suspend fun recordRecent(songId: Long, atMs: Long) = TODO()
        override suspend fun recordCompleteListen(songId: Long, atMs: Long, weekday: Int, localDate: String, playlistId: String?) = TODO()
        override suspend fun recordSkip(songId: Long) = TODO()
        override suspend fun recordRestart(songId: Long) = TODO()
        override suspend fun recordShuffleSession() = TODO()
        override fun pauseRecording() = TODO()
        override fun resumeRecording() = TODO()
        override suspend fun reset() = TODO()
        override suspend fun replaceAll(snapshot: StatsSnapshot) = TODO()
        override suspend fun mergeDelta(delta: StatsSnapshot) = TODO()
        override suspend fun relinkSongIds(idRemap: Map<Long, Long>) = TODO()
    }

    private class FakePlaylists : PlaylistRepository {
        val created = mutableListOf<Playlist>()
        override suspend fun create(name: String, songIds: List<Long>, description: String?): Playlist =
            Playlist("p${created.size}", name, songIds, description = description).also { created += it }
        override val playlists: Flow<List<Playlist>> get() = TODO()
        override fun playlist(id: String): Flow<Playlist?> = TODO()
        override suspend fun delete(id: String) = TODO()
        override suspend fun rename(id: String, name: String) = TODO()
        override suspend fun setDescription(id: String, description: String?) = TODO()
        override suspend fun setCover(id: String, jpegBytes: ByteArray?) = TODO()
        override suspend fun addSongs(id: String, songIds: List<Long>) = TODO()
        override suspend fun removeSongs(id: String, songIds: List<Long>) = TODO()
        override suspend fun reorder(id: String, songIds: List<Long>) = TODO()
        override suspend fun createFromQueue(name: String, songIds: List<Long>): Playlist = TODO()
        override suspend fun exportM3u(id: String): String = TODO()
        override suspend fun importM3u(uri: String, fileName: String, description: String): Playlist? = TODO()
        override suspend fun relinkSongIds(idRemap: Map<Long, Long>) = TODO()
        override suspend fun upsertAll(playlists: List<Playlist>) = TODO()
        override suspend fun clearAll() = TODO()
    }

    private class Fixture(songs: List<Song>, startMs: Long) {
        val library = FakeLibrary(songs)
        val stats = FakeStats()
        val playlists = FakePlaylists()
        val store = FakeStore()
        var now = startMs
        val repo = WeeklyMixRepositoryImpl(library, stats, playlists, store, Clock { now })
    }

    /** Distinct artists so the 3-per-artist cap never matters; 10 songs < limit 50 so all are picked. */
    private fun tenSongs() = (1L..10L).map { song(it, artist = "A$it") }

    @Test fun generatesOnFirstUse() = runTest {
        val f = Fixture(tenSongs(), mon0800 + 1000)
        assertTrue(f.repo.refreshIfNeeded(zone))
        val s = f.repo.state(zone).first()
        assertEquals(10, s.songs.size)
        assertEquals((1L..10L).toSet(), s.songs.map { it.id }.toSet())
        assertEquals(mon0800, s.weekStartMs)
        assertEquals(mon0800 + week, s.nextRefreshMs)
    }

    @Test fun staysStableWithinTheWeek() = runTest {
        val f = Fixture(tenSongs(), mon0800 + 1000)
        f.repo.refreshIfNeeded(zone)
        val before = f.repo.state(zone).first().songs.map { it.id }
        f.stats.snap.value = StatsSnapshot(songs = mapOf(1L to SongStats(plays = 99)))
        f.now = mon0800 + 3 * 24 * 3_600_000L
        assertFalse(f.repo.refreshIfNeeded(zone))
        assertEquals(before, f.repo.state(zone).first().songs.map { it.id })
    }

    @Test fun regeneratesAfterMondayEight() = runTest {
        val f = Fixture(tenSongs(), mon0800 + 1000)
        f.repo.refreshIfNeeded(zone)
        f.now = mon0800 + week - 1 // Monday 07:59:59.999
        assertFalse(f.repo.refreshIfNeeded(zone))
        f.now = mon0800 + week
        assertTrue(f.repo.refreshIfNeeded(zone))
        assertEquals(mon0800 + week, f.repo.state(zone).first().weekStartMs)
    }

    @Test fun regeneratesWhenClockMovesBack() = runTest {
        val f = Fixture(tenSongs(), mon0800 + week + 5)
        f.repo.refreshIfNeeded(zone)
        assertEquals(mon0800 + week, f.store.state.value!!.weekStartMs)
        f.now = mon0800 + 5
        assertTrue(f.repo.refreshIfNeeded(zone))
        assertEquals(mon0800, f.store.state.value!!.weekStartMs)
    }

    @Test fun dropsDeletedSongsWithoutRefilling() = runTest {
        val f = Fixture(tenSongs(), mon0800 + 1000)
        f.repo.refreshIfNeeded(zone)
        f.library.list.value = tenSongs().filter { it.id != 3L } + song(11, artist = "A11")
        val ids = f.repo.state(zone).first().songs.map { it.id }.toSet()
        assertEquals((1L..10L).toSet() - 3L, ids)
    }

    @Test fun mapsPathsToCurrentSongIdsAfterReindex() = runTest {
        val f = Fixture(listOf(song(1, artist = "A1"), song(2, artist = "A2")), mon0800 + 1000)
        f.repo.refreshIfNeeded(zone)
        f.library.list.value = listOf(
            song(1, artist = "A1").copy(id = 101, filePath = "/m/1.mp3"),
            song(2, artist = "A2").copy(id = 102, filePath = "/m/2.mp3"),
        )
        assertEquals(setOf(101L, 102L), f.repo.state(zone).first().songs.map { it.id }.toSet())
    }

    @Test fun emptyLibraryGivesEmptyState() = runTest {
        val f = Fixture(emptyList(), mon0800 + 1000)
        assertFalse(f.repo.refreshIfNeeded(zone))
        val s = f.repo.state(zone).first()
        assertEquals(emptyList(), s.songs)
        assertEquals(mon0800, s.weekStartMs)
        assertEquals(mon0800 + week, s.nextRefreshMs)
    }

    @Test fun saveAsPlaylistCopiesCurrentSongsAndStaysFixed() = runTest {
        val f = Fixture(tenSongs(), mon0800 + 1000)
        f.repo.refreshIfNeeded(zone)
        val expected = f.repo.state(zone).first().songs.map { it.id }
        val pl = f.repo.saveAsPlaylist("Weekly Mix")
        assertEquals("Weekly Mix", pl.name)
        assertEquals(expected, pl.songIds)
        f.now = mon0800 + week
        f.library.list.value = tenSongs().take(5)
        f.repo.refreshIfNeeded(zone)
        assertEquals(expected, f.playlists.created.single().songIds)
        assertEquals(5, f.repo.state(zone).first().songs.size)
    }
}
