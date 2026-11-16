package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartListsTest {
    private fun song(id: Long, title: String, added: Long? = null, modified: Long? = null) =
        Song(id, title, "A", "Al", 1000, "/m/$id.mp3", dateAddedMs = added, lastModifiedMs = modified)

    private val songs = listOf(
        song(1, "Old", added = 100), song(2, "New", added = 300), song(3, "Mid", added = 200),
        song(4, "Fallback", modified = 250), song(5, "Undated"),
    )

    @Test fun recentlyAddedIsNewestFirstWithMtimeFallbackAndLimit() {
        assertEquals(listOf(2L, 4L, 3L, 1L), SmartLists.recentlyAdded(songs).map { it.id })
        assertEquals(listOf(2L, 4L), SmartLists.recentlyAdded(songs, limit = 2).map { it.id })
    }

    @Test fun neverPlayedIsTitleOrdered() {
        val plays = mapOf(2L to 3)
        assertEquals(listOf(4L, 3L, 1L, 5L), SmartLists.neverPlayed(songs) { plays[it] ?: 0 }.map { it.id })
    }

    @Test fun mostPlayedRanksByCountThenTitleAndSkipsUnplayed() {
        val plays = mapOf(1L to 2, 2L to 5, 3L to 2)
        assertEquals(listOf(2L, 3L, 1L), SmartLists.mostPlayed(songs) { plays[it] ?: 0 }.map { it.id })
        assertEquals(listOf(2L), SmartLists.mostPlayed(songs, limit = 1) { plays[it] ?: 0 }.map { it.id })
    }

    @Test fun kindKeysRoundTrip() {
        SmartListKind.entries.forEach { assertEquals(it, SmartListKind.fromKey(it.key)) }
        assertEquals(SmartListKind.RecentlyAdded, SmartListKind.fromKey("bogus"))
    }
}
