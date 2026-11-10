package de.yurtemre.sonora.shared.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        SongEntity::class, FavoriteSongEntity::class, FavoriteAlbumEntity::class, FavoriteArtistEntity::class,
        PlaylistEntity::class, PlaylistEntryEntity::class, ArtistImageEntity::class,
        SongStatsEntity::class, PlaylistStatsEntity::class, WeekdayStatsEntity::class, StatsSummaryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(SonoraDatabaseConstructor::class)
abstract class SonoraDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun artistImageDao(): ArtistImageDao
    abstract fun statsDao(): StatsDao
    abstract fun relinkDao(): RelinkDao

    companion object {
        const val FILE_NAME = "sonora.db"
    }
}

@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object SonoraDatabaseConstructor : RoomDatabaseConstructor<SonoraDatabase> {
    override fun initialize(): SonoraDatabase
}

/** Finishes a platform builder (androidMain: `Room.databaseBuilder<SonoraDatabase>(ctx, ctx.getDatabasePath(FILE_NAME).path)`). */
fun RoomDatabase.Builder<SonoraDatabase>.buildSonora(): SonoraDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
