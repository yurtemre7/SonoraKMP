package de.yurtemre.sonora.shared.platform

import android.content.Context
import androidx.room.Room
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.db.buildSonora

/** Builds the Room database at `databases/sonora.db` with the bundled SQLite driver (SPEC §5.2). */
fun createSonoraDatabase(context: Context): SonoraDatabase {
    val ctx = context.applicationContext
    return Room.databaseBuilder<SonoraDatabase>(ctx, ctx.getDatabasePath(SonoraDatabase.FILE_NAME).path)
        .buildSonora()
}
