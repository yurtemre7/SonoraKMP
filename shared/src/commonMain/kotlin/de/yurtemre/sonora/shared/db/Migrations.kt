package de.yurtemre.sonora.shared.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** v2 adds `songs.date_added_ms` (nullable; filled on the next scan). */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE songs ADD COLUMN date_added_ms INTEGER")
    }
}

/** v3 adds `songs.duration_verified` (exact MP3 duration pass). */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE songs ADD COLUMN duration_verified INTEGER NOT NULL DEFAULT 0")
    }
}

/** Every schema step since v1; playlists, favourites and stats are never dropped (D-68). */
val SONORA_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
