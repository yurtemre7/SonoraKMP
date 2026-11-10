package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song

internal fun song(
    id: Long, title: String = "T$id", artist: String = "A", album: String = "Al",
    durationMs: Long = 0, track: Int? = null, disc: Int? = null, modified: Long? = null,
) = Song(id = id, title = title, artist = artist, album = album, durationMs = durationMs,
    filePath = "/m/$id.mp3", trackNumber = track, discNumber = disc, lastModifiedMs = modified)
