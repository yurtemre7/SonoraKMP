package de.yurtemre.sonora.shared.audio

/**
 * Total shown next to the seek bar, in media time (same base as the position).
 *
 * Both sources can under-report VBR MP3s without a Xing/VBRI header: ExoPlayer estimates from the first
 * frame's bitrate, and Android's media scanner was measured reporting 29.9 s for a 180 s file. Neither
 * over-reports in practice, so the larger known value wins, and the total never drops below the position.
 */
fun displayDurationMs(playerMs: Long?, libraryMs: Long?, positionMs: Long): Long {
    val player = playerMs?.takeIf { it > 0 } ?: 0L
    val library = libraryMs?.takeIf { it > 0 } ?: 0L
    return maxOf(player, library, positionMs)
}
