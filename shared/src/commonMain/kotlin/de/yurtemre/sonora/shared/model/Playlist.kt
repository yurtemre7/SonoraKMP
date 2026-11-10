package de.yurtemre.sonora.shared.model

import kotlinx.serialization.Serializable

/** A user playlist (SPEC §5.1, audit/data §1). */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<Long> = emptyList(),
    val coverPath: String? = null,
    val description: String? = null,
)
