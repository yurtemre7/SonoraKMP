package de.yurtemre.sonora.shared.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Builds a v1 database from the exported schema (Room's MigrationTestHelper is instrumentation-only on
 * Android), fills user data, then opens it with Room at the current version: Room runs the migrations
 * and validates the result against the v3 schema.
 */
class MigrationTest {
    @Test
    fun v1UserDataSurvivesMigrationToLatest() = runBlocking {
        val file = File.createTempFile("sonora-migration", ".db").also { it.delete() }
        try {
            createV1(file)
            val db = Room.databaseBuilder<SonoraDatabase>(HostContext, file.path)
                .addMigrations(*SONORA_MIGRATIONS)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
            try {
                assertEquals("Road trip", db.playlistDao().getPlaylists().single().name)
                assertEquals(7L, db.playlistDao().getEntries("p1").single().songId)
                assertEquals(7L, db.favoritesDao().getSongs().single().songId)
                assertEquals(12, db.statsDao().getSongStats(7)!!.plays)
                val song = db.songDao().getById(7)!!
                assertEquals("Song", song.title)
                assertEquals(null, song.dateAddedMs)
            } finally {
                db.close()
            }
        } finally {
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }

    private fun createV1(file: File) {
        val schema = Json.parseToJsonElement(File(SCHEMA_DIR, "1.json").readText())
            .jsonObject.getValue("database").jsonObject
        val conn = BundledSQLiteDriver().open(file.path)
        try {
            for (entity in schema.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                conn.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach {
                    conn.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { conn.execSQL(it.jsonPrimitive.content) }
            conn.execSQL(
                "INSERT INTO songs (id, title, artist, album, duration_ms, file_path, has_lyrics, rg_scanned) " +
                    "VALUES (7, 'Song', 'Artist', 'Album', 180000, '/music/song.mp3', 0, 0)",
            )
            conn.execSQL("INSERT INTO playlists (id, name, created_at_ms, position) VALUES ('p1', 'Road trip', 1, 0)")
            conn.execSQL("INSERT INTO playlist_entries (playlist_id, position, song_id, file_path) VALUES ('p1', 0, 7, '/music/song.mp3')")
            conn.execSQL("INSERT INTO favorite_songs (song_id, file_path, favorited_at_ms) VALUES (7, '/music/song.mp3', 2)")
            conn.execSQL("INSERT INTO song_stats (song_id, file_path, plays, skips, restarts) VALUES (7, '/music/song.mp3', 12, 1, 0)")
            conn.execSQL("PRAGMA user_version = 1")
        } finally {
            conn.close()
        }
    }

    private companion object {
        val SCHEMA_DIR = File("schemas/de.yurtemre.sonora.shared.db.SonoraDatabase").let {
            if (it.exists()) it else File("shared", it.path)
        }
    }
}

/** Room's Android builder needs a Context; host android.jar stubs throw, so answer the few calls it makes. */
private object HostContext : android.content.ContextWrapper(null) {
    override fun getApplicationContext(): android.content.Context = this
    override fun getSystemService(name: String): Any? = null
    override fun getDatabasePath(name: String): File = File(name)
}
