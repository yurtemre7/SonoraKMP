package de.yurtemre.sonora.shared.platform

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.db.buildSonora

/** Builds the Room database at `databases/sonora.db` with the bundled SQLite driver (SPEC §5.2). */
fun createSonoraDatabase(context: Context): SonoraDatabase {
    val ctx = context.applicationContext
    return Room.databaseBuilder<SonoraDatabase>(ctx, ctx.getDatabasePath(SonoraDatabase.FILE_NAME).path)
        // Pre-1.0 only: the library cache is rescannable, so a schema change just wipes the DB.
        // TODO(before 1.0.0): remove and write real migrations from schema 1
        .addMigrations(MIGRATION_2_3)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .buildSonora()
}

/** v3 adds `songs.duration_verified` (exact MP3 duration pass); keeps playlists, favourites and stats. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE songs ADD COLUMN duration_verified INTEGER NOT NULL DEFAULT 0")
    }
}
