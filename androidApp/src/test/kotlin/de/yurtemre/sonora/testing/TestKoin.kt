package de.yurtemre.sonora.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import de.yurtemre.sonora.di.appModule
import de.yurtemre.sonora.playback.api.MfxUiState
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.playback.api.PlayerUiState
import de.yurtemre.sonora.playback.api.QueueUiState
import de.yurtemre.sonora.playback.api.SleepTimerUiState
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.di.sharedModule
import de.yurtemre.sonora.shared.model.EqPreset
import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.BackgroundWork
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File

fun testSong(id: Long, title: String) =
    Song(id = id, title = title, artist = "Artist $id", album = "Album", durationMs = 180_000, filePath = "/music/$id.mp3")

/** Real shared module with an in-memory DB, a temp DataStore, an offline HTTP client and fake library/playback. */
fun startTestKoin(context: Context, songs: List<Song> = emptyList(), playback: PlaybackConnection = FakePlayback()) {
    stopKoin()
    val dir = File(context.cacheDir, "koin-${System.nanoTime()}").apply { mkdirs() }
    startKoin {
        allowOverride(true)
        androidContext(context)
        modules(
            sharedModule,
            appModule,
            module {
                single<SonoraDatabase> {
                    Room.inMemoryDatabaseBuilder<SonoraDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
                }
                single {
                    PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                        File(dir, "settings.preferences_pb")
                    }
                }
                single { HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }) { expectSuccess = false } }
                single<LibraryRepository> { FakeLibrary(songs) }
                single<PlaybackConnection> { playback }
            },
        )
    }
}

class FakeLibrary(songs: List<Song>) : LibraryRepository {
    private val all = MutableStateFlow(songs)
    override val songs: Flow<List<Song>> = all
    override val artistImages: Flow<Map<String, String>> = flowOf(emptyMap())
    override val isSyncing: Flow<Boolean> = flowOf(false)
    override val backgroundWork: StateFlow<BackgroundWork?> = MutableStateFlow(null)
    override suspend fun getSong(id: Long) = all.value.firstOrNull { it.id == id }
    override suspend fun getSongByPath(filePath: String) = all.value.firstOrNull { it.filePath == filePath }
    override suspend fun sync(): SyncOutcome = SyncOutcome.Success(all.value.size, 0, emptyMap())
    override suspend fun updateArtwork(artworkBySongId: Map<Long, String?>) {}
    override suspend fun updateSeedColor(songId: Long, seedColor: Int?) {}
    override suspend fun updateReplayGain(songs: List<Song>) {}
    override suspend fun clearAll() { all.value = emptyList() }
}

class FakePlayback(current: Song? = null) : PlaybackConnection {
    override val player = MutableStateFlow(PlayerUiState(isConnected = true, currentSong = current))
    override val queue = MutableStateFlow(QueueUiState())
    override val sleepTimer = MutableStateFlow<SleepTimerUiState>(SleepTimerUiState.Inactive)
    override val mfx = MutableStateFlow(MfxUiState())
    override val positionMs: Flow<Long> = flowOf(0L)
    override fun playSong(song: Song, list: List<Song>, playlistId: String?) {}
    override fun playShuffled(list: List<Song>, playlistId: String?) {}
    override fun setShuffle(enabled: Boolean) {}
    override fun toggleShuffle() {}
    override fun cycleRepeat() {}
    override fun playNext(songs: List<Song>) {}
    override fun addToQueue(songs: List<Song>) {}
    override fun removeFromQueue(entryUid: Long) {}
    override fun moveQueueItem(from: Int, to: Int) {}
    override fun insertQueueItem(songId: Long, logicalIndex: Int) {}
    override fun skipToQueueItem(entryUid: Long) {}
    override fun play() {}
    override fun pause() {}
    override fun togglePlayPause() {}
    override fun skipToNext() {}
    override fun skipToPrevious() {}
    override fun seekTo(positionMs: Long) {}
    override fun seekBy(deltaMs: Long) {}
    override fun setUserVolume(volume: Float) {}
    override fun setCustomSpeed(speed: Float) {}
    override fun setMfxPreset(preset: MfxPreset) {}
    override fun setBassBoost(enabled: Boolean) {}
    override fun setWarmth(enabled: Boolean) {}
    override fun setLoFi(enabled: Boolean) {}
    override fun setCustomEqEnabled(enabled: Boolean) {}
    override fun setCustomEqBandGain(band: Int, gain: Float) {}
    override fun applyEqPreset(preset: EqPreset) {}
    override fun resetAllEffects() {}
    override fun closePlayer() {}
    override fun undoClosePlayer() {}
    override fun startSleepTimer(durationMs: Long, finishSong: Boolean, fadeSeconds: Int) {}
    override fun extendSleepTimer(deltaMs: Long) {}
    override fun cancelSleepTimer() {}
}
