package de.yurtemre.sonora.shared.backup

import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestorePlannerTest {
    private fun song(id: Long, path: String, title: String = "t", artist: String = "a", album: String = "b", dur: Long = 100_000, fav: Boolean = false) =
        Song(id = id, title = title, artist = artist, album = album, durationMs = dur, filePath = path, isFavorite = fav, favoriteDateMs = if (fav) 5L else null)
    private fun ref(id: Long, path: String, title: String = "t", artist: String = "a", album: String = "b", dur: Long = 100_000) =
        BackupSongRef(id, path, title, artist, album, dur)

    @Test fun passOneExactPathOneToOne() {
        val lib = listOf(song(10, "/x/1.mp3"), song(11, "/x/1.mp3"))
        val m = SongMatcher.match(listOf(ref(2, "/x/1.mp3"), ref(1, "/x/1.mp3")), lib)
        // byPath keeps the lowest library id; ref 1 claims it, ref 2 falls to pass 2 (same tags) → 11.
        assertEquals(mapOf(1L to 10L, 2L to 11L), m)
    }

    @Test fun passTwoTagsWithin2000msClosestLowestIdOnTie() {
        val lib = listOf(
            song(5, "/a", title = " Song ", dur = 10_000), song(3, "/b", title = "song", dur = 12_000),
            song(4, "/c", title = "SONG", dur = 8_000), song(9, "/d", title = "song", dur = 12_001),
        )
        assertEquals(mapOf(1L to 3L), SongMatcher.match(listOf(ref(1, "/z", title = "song", dur = 10_000 + 0).copy(durationMs = 10_000)), lib.filter { it.id != 5L }))
        assertEquals(mapOf(1L to 5L), SongMatcher.match(listOf(ref(1, "/z", title = "song", dur = 10_000)), lib))
        assertEquals(emptyMap(), SongMatcher.match(listOf(ref(1, "/z", title = "song", dur = 14_002)), lib.filter { it.id != 9L }))
        assertEquals(mapOf(1L to 3L), SongMatcher.match(listOf(ref(1, "/z", title = "song", dur = 14_000)), lib.filter { it.id != 9L }))
        assertEquals(mapOf(1L to 9L), SongMatcher.match(listOf(ref(1, "/z", title = "song", dur = 14_001)), lib.filter { it.id != 3L }))
    }

    @Test fun passThreeTailAndTitleOrArtist() {
        val lib = listOf(song(7, "/sdcard/Music/Band/x.mp3", title = "New Title", artist = "Band", album = "Other", dur = 50_000))
        val r = ref(1, "C:\\Users\\me\\Music\\band\\X.MP3", title = "Old", artist = " band", album = "Album", dur = 51_500)
        assertEquals(mapOf(1L to 7L), SongMatcher.match(listOf(r), lib))
        assertEquals(emptyMap(), SongMatcher.match(listOf(r.copy(artist = "nope")), lib))
        assertEquals(emptyMap(), SongMatcher.match(listOf(r.copy(durationMs = 52_001)), lib))
        assertEquals("band/x.mp3", SongMatcher.pathTail("C:\\Users\\me\\Music\\band\\X.MP3"))
        assertEquals("x.mp3", SongMatcher.pathTail("//x.mp3"))
    }

    private val fixtureLibrary = listOf(
        // Re-indexed ids and a moved file: exact path, tags, tail.
        song(1, "/storage/emulated/0/Music/Daft Punk/Discovery/01 One More Time.mp3", "One More Time", "Daft Punk", "Discovery", 320_357),
        song(2, "/sdcard/other/Aerodynamic.mp3", "aerodynamic", "DAFT PUNK", "discovery", 211_000, fav = true),
        song(3, "/mnt/usb/First Love/03 First Love.flac", "First Love", "Utada", "FL", 259_000),
        song(4, "/other/thing.mp3", "Thing", "X", "Y", 1000),
    )

    private data class World(
        val library: List<Song>, val playlists: List<Playlist>, val albums: Map<String, Long>,
        val artists: Map<String, Long>, val settings: Map<String, SettingValue>, val stats: StatsSnapshot,
    ) {
        fun state() = RestoreState(library, playlists, albums, artists, settings)
        fun apply(p: RestorePlan) = World(
            library = library.map { s -> p.newFavoriteSongs[s.id]?.let { s.copy(isFavorite = true, favoriteDateMs = it) } ?: s },
            playlists = p.playlists ?: playlists,
            albums = p.favoriteAlbums ?: albums, artists = p.favoriteArtists ?: artists,
            settings = settings - p.settingRemovals + p.settingWrites,
            stats = p.stats ?: stats,
        )
    }

    private val initial = World(
        library = fixtureLibrary,
        playlists = listOf(
            Playlist("1730912345678", "Old name", listOf(4), coverPath = "/covers/p.jpg"),
            Playlist("local", "Local only", listOf(4)),
        ),
        albums = mapOf("discovery|||daft punk" to 1L), artists = emptyMap(),
        settings = mapOf("mfx_customEqGains" to SettingValue.Str("[1.0]"), "mfx_preset_name" to SettingValue.Str("nightcore"), "app_locale" to SettingValue.Str("system")),
        stats = StatsSnapshot(completeListens = 999),
    )

    @Test fun flutterFixtureRestorePlan() {
        val doc = BackupCodec.parse(FLUTTER_1_28_1_BACKUP)
        val plan = RestorePlanner.plan(doc, initial.state(), nowMs = 42)
        assertEquals(mapOf(1000012L to 1L, 1000013L to 2L, 1000420L to 3L), plan.idMap)
        val lateNight = plan.playlists!!.first { it.id == "1730912345678" }
        assertEquals(listOf(1L, 3L, 2L), lateNight.songIds)
        assertEquals("Late Night", lateNight.name)
        assertEquals("/covers/p.jpg", lateNight.coverPath)
        assertEquals(listOf("1730912345678", "local", "1731000000001"), plan.playlists!!.map { it.id })
        assertNull(plan.playlists!!.last().coverPath)
        assertEquals(mapOf(3L to 1730999999000L), plan.newFavoriteSongs)
        assertEquals(mapOf("discovery|||daft punk" to 1L), initial.apply(plan).albums)
        assertNull(plan.favoriteAlbums) // nothing new
        assertEquals(mapOf("宇多田ヒカル" to 1730500000000L), plan.favoriteArtists)
        assertEquals(SettingValue.Str("slowed"), plan.settingWrites["mfx_preset_name"])
        assertTrue(plan.settingRemovals.isEmpty())
        val stats = plan.stats!!
        assertEquals(57, stats.completeListens)
        assertEquals(1L, stats.firstPlayedSongId)
        assertEquals(31, stats.songs[1]!!.plays)
        assertEquals(3, stats.songs[2]!!.skips)
        assertEquals(mapOf(0 to 5, 1 to 8, 5 to 20), stats.weekdayCounts)
        assertEquals(
            RestoreCounts(
                playlistsRestored = 2, favoriteSongsRestored = 1, favoriteAlbumsAndArtistsRestored = 2,
                unmatchedSongs = 1, settingsRestored = doc.settings.size, statsRestored = true, localeChanged = true,
            ),
            plan.counts,
        )
    }

    @Test fun idempotent() {
        val doc = BackupCodec.parse(FLUTTER_1_28_1_BACKUP)
        val once = initial.apply(RestorePlanner.plan(doc, initial.state(), 42))
        val plan2 = RestorePlanner.plan(doc, once.state(), 43)
        val twice = once.apply(plan2)
        assertEquals(once, twice)
        assertNull(plan2.playlists); assertNull(plan2.favoriteArtists)
        assertTrue(plan2.newFavoriteSongs.isEmpty())
        assertEquals(false, plan2.counts.localeChanged)
    }

    @Test fun removesEffectKeysAbsentFromSettingsSection() {
        val doc = BackupCodec.parse("""{"format":"sonora-backup","version":1,"createdAt":"2026-01-01T00:00:00Z","settings":{"amoled_dark":true}}""")
        val plan = RestorePlanner.plan(doc, initial.state(), 0)
        assertEquals(setOf("mfx_customEqGains", "mfx_preset_name"), plan.settingRemovals)
        val noSection = BackupCodec.parse("""{"format":"sonora-backup","version":1,"createdAt":"2026-01-01T00:00:00Z"}""")
        val p2 = RestorePlanner.plan(noSection, initial.state(), 0)
        assertTrue(p2.settingRemovals.isEmpty() && p2.settingWrites.isEmpty() && p2.stats == null && p2.playlists == null)
    }

    @Test fun statsRemapSumsCollisionsAndMaxesLastPlayed() {
        val doc = BackupDocument(
            createdAtMs = 0, appVersion = "", settings = emptyMap(),
            songs = linkedMapOf(1L to ref(1, "/p"), 2L to ref(2, "/p2"), 3L to ref(3, "/gone", title = "zz")),
            stats = BackupStats(
                songPlayCounts = mapOf(1L to 2, 2L to 3, 3L to 7), songLastPlayedAtMs = mapOf(1L to 50, 2L to 40),
                firstPlayedSongId = 3,
            ),
        )
        // Both refs land on library song 9 only if one-to-one were broken: here ref 2 matches song 8 by tags.
        val lib = listOf(song(9, "/p"), song(8, "/q"))
        val plan = RestorePlanner.plan(doc, RestoreState(lib, emptyList(), emptyMap(), emptyMap(), emptyMap()), 0)
        assertEquals(mapOf(1L to 9L, 2L to 8L), plan.idMap)
        assertNull(plan.stats!!.firstPlayedSongId)
        assertEquals(1, plan.counts.unmatchedSongs)
        val merged = BackupStats(songPlayCounts = mapOf(1L to 2, 2L to 3), songLastPlayedAtMs = mapOf(1L to 50, 2L to 40))
            .remap(mapOf(1L to 9L, 2L to 9L))
        assertEquals(mapOf(9L to 5L), merged.songPlayCounts)
        assertEquals(mapOf(9L to 50L), merged.songLastPlayedAtMs)
        assertEquals(listOf(9L, 8L), RestorePlanner.remapIds(listOf(1, 3, 2, 1, 4), mapOf(1L to 9L, 2L to 8L, 4L to 9L)))
    }

    @Test fun errors() {
        val doc = BackupCodec.parse(FLUTTER_1_28_1_BACKUP)
        assertFailsWith<BackupError.LibraryEmpty> { RestorePlanner.plan(doc, initial.state().copy(library = emptyList()), 0) }
        assertFailsWith<BackupError.NoMatchingSongs> {
            RestorePlanner.plan(doc, initial.state().copy(library = listOf(song(1, "/nothing", title = "q"))), 0)
        }
    }

    @Test fun favoriteDateFallsBackToNowAndNeverUnfavorites() {
        val doc = BackupDocument(
            createdAtMs = 0, appVersion = "", settings = emptyMap(),
            songs = linkedMapOf(1L to ref(1, "/a"), 2L to ref(2, "/b")), favoriteSongs = mapOf(1L to null),
            favoriteAlbums = mapOf("x" to 1L, "y" to 2L),
        )
        val lib = listOf(song(1, "/a"), song(2, "/b", fav = true))
        val plan = RestorePlanner.plan(doc, RestoreState(lib, emptyList(), mapOf("x" to 9L), emptyMap(), emptyMap()), nowMs = 1234)
        assertEquals(mapOf(1L to 1234L), plan.newFavoriteSongs)
        assertEquals(mapOf("x" to 9L, "y" to 2L), plan.favoriteAlbums)
        assertNull(plan.playlists)
    }
}
