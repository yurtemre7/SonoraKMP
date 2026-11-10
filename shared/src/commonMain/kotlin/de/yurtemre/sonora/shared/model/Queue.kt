package de.yurtemre.sonora.shared.model

import kotlinx.serialization.Serializable

/** One queue slot; [uid] distinguishes duplicate songs (PLAY-02). */
@Serializable
data class QueueEntry(val uid: Long, val songId: Long)

/** Immutable queue snapshot (PLAY-02/05/06). */
@Serializable
data class QueueState(
    val entries: List<QueueEntry> = emptyList(),
    val original: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val isShuffled: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val playlistContext: String? = null,
    val nextUid: Long = 0,
    /** FIFO play-next: uid of the last play-next entry, valid while [playNextForUid] is still current. */
    val playNextAnchorUid: Long? = null,
    val playNextForUid: Long? = null,
)
