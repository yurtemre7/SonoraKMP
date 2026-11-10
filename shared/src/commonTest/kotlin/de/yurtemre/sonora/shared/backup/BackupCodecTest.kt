package de.yurtemre.sonora.shared.backup

import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCodecTest {
    private fun minimal(extra: String = "") =
        """{"format":"sonora-backup","version":1,"createdAt":"2026-03-07T12:34:56.789Z"$extra}"""

    @Test fun flutterFixtureParses() {
        val doc = BackupCodec.parse(FLUTTER_1_28_1_BACKUP)
        assertEquals(1_794_166_927_123L, doc.createdAtMs)
        assertEquals("1.28.1+128", doc.appVersion)
        assertEquals(listOf(1000012L, 1000013L, 1000420L, 1000777L), doc.songs.keys.toList())
        assertEquals(SettingValue.Int(4294198070), doc.settings["default_theme_color"])
        assertEquals(SettingValue.Decimal(1.0), doc.settings["speed"])
        assertEquals(SettingValue.StrList(listOf("0.0", "1.5", "-2.0", "0.5", "3.0")), doc.settings["mfx_customEqGains"])
        assertEquals(2, doc.playlists!!.size)
        assertNull(doc.playlists!![0].description)
        assertEquals(mapOf(1000420L to 1730999999000L, 1000777L to null), doc.favoriteSongs)
        assertEquals(31L, doc.stats!!.songPlayCounts[1000012])
        assertEquals(mapOf(0L to 5L, 1L to 8L, 5L to 20L), doc.stats!!.weeklyPlayCounts)
    }

    @Test fun flutterFixtureLegacyMapping() {
        val stored = SettingsWhitelist.fromBackup(BackupCodec.parse(FLUTTER_1_28_1_BACKUP).settings)
        assertEquals(SettingValue.Str("system"), stored["theme_color_source"])
        assertEquals(SettingValue.Str("slowed"), stored["mfx_preset_name"])
        assertEquals(SettingValue.Str("[0.0,1.5,-2.0,0.5,3.0]"), stored["mfx_customEqGains"])
        for (k in SettingsWhitelist.importOnlyKeys) assertFalse(k in stored, k)
        assertEquals(SettingValue.Bool(true), stored["use_material_you"])
    }

    @Test fun legacyMappings() {
        fun src(vararg p: Pair<String, Boolean>) = SettingsWhitelist.legacyColorSource(p.associate { it.first to SettingValue.Bool(it.second) })
        assertNull(src())
        assertEquals("album_art", src("use_material_you" to false))
        assertEquals("custom", src("use_dynamic_theme" to false, "use_material_you" to false))
        val explicit = SettingsWhitelist.fromBackup(mapOf("theme_color_source" to SettingValue.Str("afterglow"), "use_material_you" to SettingValue.Bool(true)))
        assertEquals(SettingValue.Str("afterglow"), explicit["theme_color_source"])
        fun preset(vararg on: String) = SettingsWhitelist.legacyPreset(on.associateWith { SettingValue.Bool(true) })?.storageName
        assertEquals("superSlowed", preset("mfx_isNightcore", "mfx_isSuperSlowed", "mfx_isSlowed"))
        assertEquals("slowed", preset("mfx_isSlowed", "mfx_isSuperSpedUp"))
        assertEquals("superSpedUp", preset("mfx_isSpedUp", "mfx_isSuperSpedUp"))
        assertEquals("nightcore", preset("mfx_isNightcore"))
        assertEquals("spedUp110", SettingsWhitelist.legacyPreset(mapOf("mfx_preset" to SettingValue.Decimal(1.1)))?.storageName)
        val named = SettingsWhitelist.fromBackup(mapOf("mfx_preset_name" to SettingValue.Str("nightcore"), "mfx_isSlowed" to SettingValue.Bool(true)))
        assertEquals(SettingValue.Str("nightcore"), named["mfx_preset_name"])
    }

    @Test fun bomAndUtf8() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + minimal().encodeToByteArray()
        assertEquals(minimal(), BackupCodec.decodeFile(bytes))
        BackupCodec.parse("\uFEFF" + minimal())
        assertFailsWith<BackupError.InvalidJson> { BackupCodec.decodeFile(byteArrayOf(0x7B, 0xC3.toByte(), 0x28)) }
    }

    @Test fun errorKinds() {
        assertFailsWith<BackupError.InvalidJson> { BackupCodec.parse("{not json") }
        assertFailsWith<BackupError.NotABackup> { BackupCodec.parse("[]") }
        assertFailsWith<BackupError.NotABackup> { BackupCodec.parse("""{"format":"other","version":1}""") }
        assertFailsWith<BackupError.UnsupportedVersion> { BackupCodec.parse("""{"format":"sonora-backup","version":2}""") }
        assertFailsWith<BackupError.UnsupportedVersion> { BackupCodec.parse("""{"format":"sonora-backup","version":"1"}""") }
        assertFailsWith<BackupError.UnsupportedVersion> { BackupCodec.parse("""{"format":"sonora-backup","version":1.5}""") }
        assertFailsWith<BackupError.UnsupportedVersion> { BackupCodec.parse("""{"format":"sonora-backup"}""") }
        val malformed = listOf(
            """{"format":"sonora-backup","version":1,"createdAt":"yesterday"}""",
            """{"format":"sonora-backup","version":1}""",
            minimal(""","songs":{}"""),
            minimal(""","songs":[{"id":"1","path":"","title":"","artist":"","album":"","durationMs":0}]"""),
            minimal(""","songs":[{"id":1,"path":"","title":"","artist":"","album":"","durationMs":1.5}]"""),
            minimal(""","songs":[{"id":1,"title":"","artist":"","album":"","durationMs":0}]"""),
            minimal(""","settings":[]"""),
            minimal(""","settings":{"amoled_dark":1}"""),
            minimal(""","settings":{"default_start_page":1.0}"""),
            minimal(""","settings":{"theme_mode":true}"""),
            minimal(""","settings":{"volume":"1"}"""),
            minimal(""","settings":{"mfx_customEqGains":[1.0]}"""),
            minimal(""","playlists":[{"id":1,"name":"a","songIds":[]}]"""),
            minimal(""","playlists":[{"id":"p","name":"a","description":3,"songIds":[]}]"""),
            minimal(""","playlists":[{"id":"p","name":"a"}]"""),
            minimal(""","favorites":{"songs":[{"id":1,"favoriteDateMs":"x"}]}"""),
            minimal(""","favorites":{"albums":{"a":true}}"""),
            minimal(""","stats":{"first_played_date":5}"""),
            minimal(""","stats":{"song_play_counts":{"abc":1}}"""),
            minimal(""","stats":{"complete_song_listens":"2"}"""),
        )
        for (m in malformed) assertFailsWith<BackupError.Malformed>(m) { BackupCodec.parse(m) }
    }

    @Test fun lenientWhereFlutterIsLenient() {
        val doc = BackupCodec.parse(minimal(""","settings":{"unknown_key":1,"theme_mode":null,"volume":1},"songs":null,"stats":{"first_played_song_id":null}"""))
        assertEquals(mapOf<String, SettingValue>("volume" to SettingValue.Decimal(1.0)), doc.settings)
        assertTrue(doc.songs.isEmpty())
        assertEquals(-1, doc.stats!!.firstPlayedSongId)
        assertNull(doc.playlists); assertNull(doc.favoriteSongs)
        assertFalse(BackupCodec.parse(minimal()).hasSettings)
        assertEquals(1_000L, BackupCodec.parseDate("1970-01-01T00:00:01"))
    }

    private fun song(id: Long, fav: Boolean = false) = Song(
        id = id, title = "T$id", artist = "A", album = "B", durationMs = 1000L * id,
        filePath = "/m/$id.mp3", isFavorite = fav, favoriteDateMs = if (fav) 77L else null,
    )

    @Test fun exportValidatesUnderFlutterRulesAndRoundTrips() {
        val library = listOf(song(1), song(2, fav = true), song(3), song(4))
        val stored = mapOf(
            "theme_color_source" to SettingValue.Str("afterglow"),
            "use_material_you" to SettingValue.Bool(false),
            "use_dynamic_theme" to SettingValue.Bool(true),
            "default_theme_color" to SettingValue.Int(0xFFE5573B),
            "volume" to SettingValue.Decimal(1.0),
            "mfx_preset_name" to SettingValue.Str("slowed"),
            "mfx_customEqGains" to SettingValue.Str("[0.0,1.5,-2]"),
            "scan_folder_path" to SettingValue.Str("/secret"),
            "amoled_dark" to SettingValue.Int(1),
        )
        val doc = BackupCodec.buildExport(
            library = library,
            playlists = listOf(Playlist("p1", "Mix", listOf(3, 99, 1), coverPath = "/c.jpg")),
            favoriteAlbums = mapOf("b|||a" to 5L), favoriteArtists = emptyMap(),
            stats = StatsSnapshot(
                songs = mapOf(4L to SongStats(plays = 2, lastPlayedAtMs = 9), 99L to SongStats(plays = 1)),
                weekdayCounts = mapOf(3 to 2), firstPlayedSongId = 99,
            ),
            storedSettings = stored, appVersion = "1.29.0+512", nowMs = 1_762_630_927_000,
        )
        assertEquals(listOf(3L, 1L, 2L, 4L), doc.songs.keys.toList())
        assertEquals(listOf(3L, 1L), doc.playlists!!.single().songIds)
        assertEquals(-1, doc.stats!!.firstPlayedSongId)
        val json = BackupCodec.encode(doc)
        assertTrue(json.startsWith("{\n  \"format\": \"sonora-backup\",\n  \"version\": 1,\n  \"createdAt\": \"2025-11-08T19:42:07.000Z\""), json)
        assertTrue("\"volume\": 1.0" in json)

        // Flutter rules: every Flutter whitelist key present has Flutter's type; unknown keys are ignored.
        val settings = Json.parseToJsonElement(json).jsonObject["settings"] as JsonObject
        assertFalse("scan_folder_path" in settings); assertFalse("amoled_dark" in settings)
        val reparsed = BackupCodec.parse(json)
        for ((k, v) in reparsed.settings) {
            SettingsWhitelist.flutterKeys[k]?.let { assertTrue(v.matches(it), k) }
        }
        assertEquals(SettingValue.StrList(listOf("0.0", "1.5", "-2.0")), reparsed.settings["mfx_customEqGains"])
        assertEquals(doc, reparsed)
        assertEquals(json, BackupCodec.encode(reparsed))
        assertEquals("[0.0,1.5,-2.0]", (SettingsWhitelist.fromBackup(reparsed.settings)["mfx_customEqGains"] as SettingValue.Str).value)
    }

    @Test fun flutterFixtureReEncodesStably() {
        val doc = BackupCodec.parse(FLUTTER_1_28_1_BACKUP)
        assertEquals(doc, BackupCodec.parse(BackupCodec.encode(doc)))
    }

    @Test fun exportEmptyLibrary() {
        assertFailsWith<BackupError.LibraryEmpty> {
            BackupCodec.buildExport(emptyList(), emptyList(), emptyMap(), emptyMap(), StatsSnapshot(), emptyMap(), "x", 0)
        }
        assertEquals("sonora-backup-2026-03-07.json", BackupCodec.defaultFileName(2026, 3, 7))
    }
}
