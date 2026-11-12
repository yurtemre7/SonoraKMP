package de.yurtemre.sonora.shared.repo.impl

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import de.yurtemre.sonora.shared.audio.Mp3Duration
import de.yurtemre.sonora.shared.audio.replaygain.ReplayGainReader
import kotlin.math.abs
import de.yurtemre.sonora.shared.db.ArtistImageEntity
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.db.toEntity
import de.yurtemre.sonora.shared.db.toModel
import de.yurtemre.sonora.shared.library.AlbumGrouper
import de.yurtemre.sonora.shared.library.ArtistGrouper
import de.yurtemre.sonora.shared.library.LibrarySearchIndex
import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.LibraryScanner
import de.yurtemre.sonora.shared.repo.api.ScanEnrichment
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** LIB-04/08/21/22/23 library cache + sync orchestration (D-19, D-20, D-49). */
class LibraryRepositoryImpl(
    private val db: SonoraDatabase,
    private val scanner: LibraryScanner,
    private val settings: LibrarySettings,
    private val platform: LibraryPlatform,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val seedStartDelayMs: Long = 3_000,
    private val seedItemDelayMs: Long = 100,
) : LibraryRepository {
    private val songDao = db.songDao()
    private val syncMutex = Mutex()
    private val _isSyncing = MutableStateFlow(false)
    private var seedJob: Job? = null
    private var enrichJob: Job? = null

    override val songs: Flow<List<Song>> = combine(songDao.observeAll(), db.favoritesDao().observeSongs()) { rows, favs ->
        val byId = favs.associateBy { it.songId }
        rows.map { it.toModel(byId[it.id]) }
    }
    override val artistImages: Flow<Map<String, String>> =
        db.artistImageDao().observeAll().map { l -> l.associate { it.artistKey to it.path } }
    override val isSyncing: Flow<Boolean> = _isSyncing.asStateFlow()

    val albums: Flow<List<AlbumGroup>> = songs.map { AlbumGrouper.build(it) }
    val artists: Flow<List<ArtistGroup>> = combine(songs, artistImages) { s, img -> ArtistGrouper.build(s, AlbumGrouper.build(s), img) }
    val searchIndex: Flow<LibrarySearchIndex> = songs.map { LibrarySearchIndex(it) }

    init {
        // D-20: patch rows when background art extraction finishes.
        scope.launch { platform.backgroundArt.collect { m -> m.forEach { (album, path) -> songDao.updateArtworkForAlbum(album, path) } } }
    }

    override suspend fun getSong(id: Long): Song? = songDao.getById(id)?.let { e ->
        e.toModel(db.favoritesDao().getSongs().firstOrNull { it.songId == id })
    }
    override suspend fun getSongByPath(filePath: String): Song? = songDao.getByPath(filePath)?.let { getSong(it.id) }

    override suspend fun sync(): SyncOutcome = syncMutex.withLock {
        _isSyncing.value = true
        val start = clock.nowMs()
        try {
            val cached = songDao.getAll().map { it.toModel() }
            val (path, uri) = settings.scanFolder()
            val result = try {
                scanner.scan(path, uri, cached)
            } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                return SyncOutcome.Failed(t) // D-19: cache untouched
            } ?: return SyncOutcome.FolderMissing
            val merged = merge(cached, result.songs)
            val idRemap = db.useWriterConnection { c ->
                c.immediateTransaction {
                    val oldIdByPath = cached.associate { it.filePath to it.id }
                    songDao.replaceAll(merged.map { it.toEntity() })
                    result.artistImages?.let { m -> db.artistImageDao().replaceAll(m.map { ArtistImageEntity(it.key, it.value) }) }
                    db.relinkDao().relinkAll() // D-49
                    merged.mapNotNull { s -> oldIdByPath[s.filePath]?.takeIf { it != s.id }?.let { it to s.id } }.toMap()
                }
            }
            val duration = clock.nowMs() - start
            settings.recordSync(clock.nowMs(), duration)
            // Only MediaStore rows + one DB write block; everything slow runs after the songs are shown.
            scope.launch { fillReplayGain() }
            scope.launch { fillDurations() }
            startEnrichment(path, uri, merged)
            SyncOutcome.Success(merged.size, duration, idRemap)
        } catch (c: CancellationException) { throw c } catch (t: Throwable) {
            SyncOutcome.Failed(t)
        } finally {
            _isSyncing.value = false
        }
    }

    /** LIB-04 unchanged rule; changed rows carry seed colour by path. */
    private fun merge(cached: List<Song>, scanned: List<Song>): List<Song> {
        val byPath = cached.associateBy { it.filePath }
        return scanned.map { s ->
            val old = byPath[s.filePath] ?: return@map s
            val unchanged = old.lastModifiedMs == s.lastModifiedMs && old.fileSize == s.fileSize &&
                (s.artworkPath == null || s.artworkPath == old.artworkPath)
            if (unchanged) old.copy(id = s.id, albumId = s.albumId ?: old.albumId, dateAddedMs = s.dateAddedMs ?: old.dateAddedMs,
                trackNumber = s.trackNumber, discNumber = s.discNumber, albumArtist = s.albumArtist ?: old.albumArtist, isFavorite = false, favoriteDateMs = null)
            else s.copy(seedColor = s.seedColor ?: old.seedColor.takeIf { s.artworkPath == old.artworkPath })
        }
    }

    /** Background: covers, lyrics flags, artist images (scanner.enrich), then the seed pass. */
    private fun startEnrichment(path: String?, uri: String?, songs: List<Song>) {
        enrichJob?.cancel()
        enrichJob = scope.launch {
            val e = try { scanner.enrich(path, uri, songs) } catch (c: CancellationException) { throw c } catch (t: Throwable) { null }
            if (e != null) applyEnrichment(songs, e)
            startSeedPass()
        }
    }

    private suspend fun applyEnrichment(songs: List<Song>, e: ScanEnrichment) {
        db.useWriterConnection { c ->
            c.immediateTransaction {
                val current = songDao.getAll().associateBy { it.id }
                e.artworkByAlbum.forEach { (album, art) ->
                    if (songs.any { it.albumId == album && current[it.id]?.artworkPath != art }) songDao.updateArtworkForAlbum(album, art)
                }
                val (with, without) = songs.mapNotNull { current[it.id] }.partition { it.filePath in e.lyricsPaths }
                with.filter { !it.hasLyrics }.map { it.id }.takeIf { it.isNotEmpty() }?.let { songDao.updateHasLyrics(it, true) }
                without.filter { it.hasLyrics }.map { it.id }.takeIf { it.isNotEmpty() }?.let { songDao.updateHasLyrics(it, false) }
                db.artistImageDao().replaceAll(e.artistImages.map { ArtistImageEntity(it.key, it.value) })
            }
        }
    }

    /** LIB-08: fan-out 4; IO failure stays unscanned. */
    suspend fun fillReplayGain() = withContext(Dispatchers.IO) {
        val pending = songDao.getAll().filter { !it.rgScanned }.map { it.toModel() }
        if (pending.isEmpty()) return@withContext
        val gate = Semaphore(4)
        val done = pending.map { s ->
            async {
                gate.withPermit {
                    val r = ReplayGainReader.tryRead { platform.openByteSource(s.filePath) }
                    if (r.failed) null else s.copy(replayGain = r.info, replayGainScanned = true)
                }
            }
        }.awaitAll().filterNotNull()
        updateReplayGain(done)
    }

    /**
     * Exact MP3 lengths (MediaStore/ExoPlayer misread header-less VBR files). Runs once per file: merge keeps
     * the verified flag while path, size and mtime are unchanged. Only differences over 1 s are written.
     */
    suspend fun fillDurations() = withContext(Dispatchers.IO) {
        val pending = songDao.getAll().filter { !it.durationVerified && it.filePath.endsWith(".mp3", ignoreCase = true) }
        if (pending.isEmpty()) return@withContext
        val gate = Semaphore(2)
        val corrected = pending.map { s ->
            async {
                gate.withPermit {
                    val ms = runCatching {
                        val src = platform.openByteSource(s.filePath)
                        try { Mp3Duration.measureMs(src) } finally { (src as? AutoCloseable)?.close() }
                    }.getOrNull()
                    if (ms != null && abs(ms - s.durationMs) > 1_000) s.id to ms else null
                }
            }
        }.awaitAll().filterNotNull().toMap()
        db.useWriterConnection { c ->
            c.immediateTransaction {
                corrected.forEach { (id, ms) -> songDao.updateVerifiedDuration(id, ms) }
                pending.map { it.id }.filter { it !in corrected }.chunked(500).forEach { songDao.markDurationVerified(it) }
            }
        }
    }

    /** LIB-22: background seed pass, 3 s after load, 100 ms between images, one batch write. */
    fun startSeedPass() {
        seedJob?.cancel()
        seedJob = scope.launch {
            delay(seedStartDelayMs)
            val colorless = settings.colorlessPaths()
            val todo = songDao.getAll().filter { it.seedColor == null && it.artworkPath != null && it.artworkPath !in colorless }
            val byArt = todo.groupBy { it.artworkPath!! }
            val found = mutableMapOf<Long, Int>(); val failed = mutableSetOf<String>()
            for ((art, rows) in byArt) {
                val c = runCatching { platform.extractSeedColor(art) }.getOrNull()
                if (c == null) failed += art else rows.forEach { found[it.id] = c }
                delay(seedItemDelayMs)
            }
            if (found.isNotEmpty()) db.useWriterConnection { t -> t.immediateTransaction { found.forEach { (id, c) -> songDao.updateSeedColor(id, c) } } }
            settings.addColorless(failed)
        }
    }

    /** LIB-21 sync reminder. */
    suspend fun shouldShowSyncReminder(): Boolean {
        val last = settings.lastSyncMs() ?: return false
        val now = clock.nowMs()
        return songDao.getAll().isNotEmpty() && last < now - REMINDER_MS && now >= settings.postponeUntilMs()
    }

    suspend fun postponeSyncReminder() = settings.setPostponeUntil(clock.nowMs() + REMINDER_MS)

    override suspend fun updateArtwork(artworkBySongId: Map<Long, String?>) {
        if (artworkBySongId.isEmpty()) return
        db.useWriterConnection { c ->
            c.immediateTransaction {
                val rows = songDao.getAll().filter { it.id in artworkBySongId }
                songDao.upsertAll(rows.map { it.copy(artworkPath = artworkBySongId[it.id]) })
            }
        }
    }

    override suspend fun updateSeedColor(songId: Long, seedColor: Int?) = songDao.updateSeedColor(songId, seedColor)

    override suspend fun updateReplayGain(songs: List<Song>) {
        if (songs.isEmpty()) return
        db.useWriterConnection { c ->
            c.immediateTransaction {
                val current = songDao.getAll().associateBy { it.id }
                songDao.upsertAll(songs.mapNotNull { s ->
                    current[s.id]?.let { e ->
                        val r = s.toEntity()
                        e.copy(rgTrackGain = r.rgTrackGain, rgAlbumGain = r.rgAlbumGain, rgTrackPeak = r.rgTrackPeak,
                            rgAlbumPeak = r.rgAlbumPeak, rgScanned = r.rgScanned)
                    }
                })
            }
        }
    }

    override suspend fun clearAll() {
        songDao.clearAll(); db.artistImageDao().clearAll()
    }

    companion object {
        const val REMINDER_MS = 30L * 24 * 60 * 60 * 1000
    }
}
