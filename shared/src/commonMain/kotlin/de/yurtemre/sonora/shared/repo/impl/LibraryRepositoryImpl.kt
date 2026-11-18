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
import de.yurtemre.sonora.shared.library.TagEdit
import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.BackgroundWork
import de.yurtemre.sonora.shared.repo.api.BackgroundWork.Stage
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.LibraryScanner
import de.yurtemre.sonora.shared.repo.api.EnrichmentPart
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
    private var rgJob: Job? = null
    private var durationJob: Job? = null

    override val songs: Flow<List<Song>> = combine(songDao.observeAll(), db.favoritesDao().observeSongs()) { rows, favs ->
        val byId = favs.associateBy { it.songId }
        rows.map { it.toModel(byId[it.id]) }
    }
    override val artistImages: Flow<Map<String, String>> =
        db.artistImageDao().observeAll().map { l -> l.associate { it.artistKey to it.path } }
    override val isSyncing: Flow<Boolean> = _isSyncing.asStateFlow()

    /** Running stages → latest progress; published (lowest stage first) at most every [PROGRESS_THROTTLE_MS]. */
    private val activeWork = MutableStateFlow<Map<Stage, BackgroundWork>>(emptyMap())
    private val _backgroundWork = MutableStateFlow<BackgroundWork?>(null)
    override val backgroundWork: StateFlow<BackgroundWork?> = _backgroundWork.asStateFlow()

    private fun report(stage: Stage, done: Int, total: Int) {
        if (total <= 0) return
        activeWork.update { it + (stage to BackgroundWork(stage, done.coerceIn(0, total), total)) }
    }
    private fun advance(stage: Stage, total: Int) =
        activeWork.update { m -> m + (stage to BackgroundWork(stage, ((m[stage]?.done ?: 0) + 1).coerceAtMost(total), total)) }
    private fun finish(vararg stages: Stage) = activeWork.update { it - stages.toSet() }

    val albums: Flow<List<AlbumGroup>> = songs.map { AlbumGrouper.build(it) }
    val artists: Flow<List<ArtistGroup>> = combine(songs, artistImages) { s, img -> ArtistGrouper.build(s, AlbumGrouper.build(s), img) }
    val searchIndex: Flow<LibrarySearchIndex> = songs.map { LibrarySearchIndex(it) }

    init {
        // D-20: patch rows when background art extraction finishes.
        scope.launch { platform.backgroundArt.collect { m -> m.forEach { (album, path) -> songDao.updateArtworkForAlbum(album, path) } } }
        // StateFlow collection is conflated, so the delay throttles emissions to >= 150 ms apart.
        scope.launch {
            activeWork.collect { m ->
                _backgroundWork.value = m.values.minByOrNull { it.stage.ordinal }
                delay(PROGRESS_THROTTLE_MS)
            }
        }
    }

    override suspend fun getSong(id: Long): Song? = songDao.getById(id)?.let { e ->
        e.toModel(db.favoritesDao().getSongs().firstOrNull { it.songId == id })
    }
    override suspend fun getSongByPath(filePath: String): Song? = songDao.getByPath(filePath)?.let { getSong(it.id) }

    override suspend fun sync(): SyncOutcome = syncMutex.withLock {
        _isSyncing.value = true
        val start = clock.nowMs()
        try {
            val cachedEntities = songDao.getAll()
            val cached = cachedEntities.map { it.toModel() }
            val (path, uri) = settings.scanFolder()
            val result = try {
                scanner.scan(path, uri, cached)
            } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                return SyncOutcome.Failed(t) // D-19: cache untouched
            } ?: return SyncOutcome.FolderMissing
            val merged = merge(cached, result.songs)
            val next = merged.map { it.toEntity() }
            // Write only the difference: an unchanged library issues no write, so nothing re-emits and nothing is relinked.
            val oldById = cachedEntities.associateBy { it.id }
            val nextById = next.associateBy { it.id }
            // Rows gone or moved to another path go first, so a reused path can't hit the unique file_path index.
            val stale = cachedEntities.filter { nextById[it.id]?.filePath != it.filePath }.map { it.id }
            val changed = next.filter { oldById[it.id] != it }
            val idRemap = db.useWriterConnection { c ->
                c.immediateTransaction {
                    stale.chunked(DB_CHUNK).forEach { songDao.deleteByIds(it) }
                    if (changed.isNotEmpty()) songDao.upsertAll(changed)
                    result.artistImages?.let { m -> replaceArtistImages(m) }
                    if (stale.isNotEmpty() || changed.isNotEmpty()) db.relinkDao().relinkAll() // D-49
                    val oldIdByPath = cached.associate { it.filePath to it.id }
                    merged.mapNotNull { s -> oldIdByPath[s.filePath]?.takeIf { it != s.id }?.let { it to s.id } }.toMap()
                }
            }
            val duration = clock.nowMs() - start
            settings.recordSync(clock.nowMs(), duration)
            // Only MediaStore rows + one DB write block; everything slow runs after the songs are shown.
            rgJob?.cancel(); rgJob = scope.launch { fillReplayGain() }
            durationJob?.cancel(); durationJob = scope.launch { fillDurations() }
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
            // The MediaStore scan doesn't read genre; keep the one already on the row (e.g. from the tag editor).
            else s.copy(seedColor = s.seedColor ?: old.seedColor.takeIf { s.artworkPath == old.artworkPath }, genre = s.genre ?: old.genre)
        }
    }

    /** Background: covers, lyrics flags, artist images (scanner.enrich), then the seed pass. */
    private fun startEnrichment(path: String?, uri: String?, songs: List<Song>) {
        enrichJob?.cancel()
        enrichJob = scope.launch {
            try {
                // Each part is written (and so shown) the moment it is ready, not after the slowest one.
                scanner.enrichIncremental(path, uri, songs, { stage, done, total -> report(stage, done, total) }) { part ->
                    applyPart(songs, part)
                    when (part) {
                        is EnrichmentPart.Artwork -> Unit // more batches may follow; the stage ends with enrichment
                        is EnrichmentPart.Lyrics -> finish(Stage.Lyrics)
                        is EnrichmentPart.ArtistImages -> finish(Stage.ArtistImages)
                    }
                }
            } catch (c: CancellationException) { throw c } catch (_: Throwable) {
            } finally {
                finish(Stage.Artwork, Stage.Lyrics, Stage.ArtistImages)
            }
            startSeedPass()
        }
    }

    private suspend fun applyPart(songs: List<Song>, part: EnrichmentPart) {
        when (part) {
            is EnrichmentPart.Artwork -> if (part.byAlbum.isNotEmpty()) db.useWriterConnection { c ->
                // The DAO skips rows that already hold the path, so repeats are free.
                c.immediateTransaction { part.byAlbum.forEach { (album, art) -> songDao.updateArtworkForAlbum(album, art) } }
            }
            is EnrichmentPart.Lyrics -> {
                val ids = songs.mapTo(HashSet()) { it.id }
                val current = songDao.getAll().filter { it.id in ids }
                val gained = current.filter { it.filePath in part.withLyrics && !it.hasLyrics }.map { it.id }
                val lost = current.filter { it.filePath !in part.withLyrics && it.hasLyrics }.map { it.id }
                if (gained.isNotEmpty() || lost.isNotEmpty()) db.useWriterConnection { c ->
                    c.immediateTransaction {
                        gained.chunked(DB_CHUNK).forEach { songDao.updateHasLyrics(it, true) }
                        lost.chunked(DB_CHUNK).forEach { songDao.updateHasLyrics(it, false) }
                    }
                }
            }
            is EnrichmentPart.ArtistImages -> replaceArtistImages(part.images)
        }
    }

    /** Replaces the artist images only when they differ, so an unchanged scan doesn't re-emit the table. */
    private suspend fun replaceArtistImages(images: Map<String, String>) {
        val dao = db.artistImageDao()
        if (dao.getAll().associate { it.artistKey to it.path } == images) return
        dao.replaceAll(images.map { ArtistImageEntity(it.key, it.value) })
    }

    /** LIB-08: fan-out 4; IO failure stays unscanned. Written in batches so progress survives cancellation. */
    suspend fun fillReplayGain() = withContext(Dispatchers.IO) {
        val pending = songDao.getAll().filter { !it.rgScanned }.map { it.toModel() }
        if (pending.isEmpty()) return@withContext
        report(Stage.ReplayGain, 0, pending.size)
        try {
            val gate = Semaphore(4)
            for (batch in pending.chunked(RG_BATCH)) {
                val done = batch.map { s ->
                    async {
                        gate.withPermit {
                            val r = ReplayGainReader.tryRead { platform.openByteSource(s.filePath) }
                            advance(Stage.ReplayGain, pending.size)
                            if (r.failed) null else s.copy(replayGain = r.info, replayGainScanned = true)
                        }
                    }
                }.awaitAll().filterNotNull()
                updateReplayGain(done)
            }
        } finally { finish(Stage.ReplayGain) }
    }

    /**
     * Exact MP3 lengths (MediaStore/ExoPlayer misread header-less VBR files). Runs once per file: merge keeps
     * the verified flag while path, size and mtime are unchanged. Only differences over 1 s are written.
     */
    suspend fun fillDurations() = withContext(Dispatchers.IO) {
        val pending = songDao.getAll().filter { !it.durationVerified && it.filePath.endsWith(".mp3", ignoreCase = true) }
        if (pending.isEmpty()) return@withContext
        report(Stage.Durations, 0, pending.size)
        try { fillDurations(pending) } finally { finish(Stage.Durations) }
    }

    private suspend fun fillDurations(pending: List<de.yurtemre.sonora.shared.db.SongEntity>) = coroutineScope {
        val gate = Semaphore(2)
        // Batched writes: corrected lengths show up while the rest is still being measured.
        for (batch in pending.chunked(DURATION_BATCH)) {
            val corrected = batch.map { s ->
                async {
                    gate.withPermit {
                        val ms = runCatching {
                            val src = platform.openByteSource(s.filePath)
                            try { Mp3Duration.measureMs(src) } finally { (src as? AutoCloseable)?.close() }
                        }.getOrNull()
                        advance(Stage.Durations, pending.size)
                        if (ms != null && abs(ms - s.durationMs) > 1_000) s.id to ms else null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
            db.useWriterConnection { c ->
                c.immediateTransaction {
                    corrected.forEach { (id, ms) -> songDao.updateVerifiedDuration(id, ms) }
                    batch.map { it.id }.filter { it !in corrected }.chunked(DB_CHUNK).forEach { songDao.markDurationVerified(it) }
                }
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
            // Colours are written every [SEED_BATCH] images (also when cancelled), so the UI tints as they are found.
            suspend fun flush() {
                if (found.isEmpty()) return
                val batch = found.toMap(); found.clear()
                db.useWriterConnection { t -> t.immediateTransaction { batch.forEach { (id, c) -> songDao.updateSeedColor(id, c) } } }
            }
            try {
                var i = 0
                report(Stage.Colors, 0, byArt.size)
                for ((art, rows) in byArt) {
                    val c = runCatching { platform.extractSeedColor(art) }.getOrNull()
                    if (c == null) failed += art else rows.forEach { found[it.id] = c }
                    report(Stage.Colors, ++i, byArt.size)
                    if (i % SEED_BATCH == 0) flush()
                    delay(seedItemDelayMs)
                }
            } finally {
                finish(Stage.Colors)
                withContext(NonCancellable) { flush(); if (failed.isNotEmpty()) settings.addColorless(failed) }
            }
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

    override suspend fun updateTags(songId: Long, edit: TagEdit) {
        val e = edit.normalized()
        // Same fallbacks the scanners use for missing tags.
        // Under the sync mutex so an in-flight sync can't overwrite this row with metadata read before the edit.
        syncMutex.withLock { songDao.updateTags(
            id = songId, title = e.title, artist = e.artist.ifEmpty { TagEdit.UNKNOWN_ARTIST },
            album = e.album.ifEmpty { TagEdit.UNKNOWN_ALBUM }, albumArtist = e.albumArtist.ifEmpty { null },
            genre = e.genre.ifEmpty { null }, year = e.yearOrNull(), track = e.trackOrNull(), disc = e.discOrNull(),
        ) }
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
        const val PROGRESS_THROTTLE_MS = 150L
        private const val DB_CHUNK = 500
        private const val RG_BATCH = 200
        private const val DURATION_BATCH = 50
        private const val SEED_BATCH = 20
        const val REMINDER_MS = 30L * 24 * 60 * 60 * 1000
    }
}
