package de.yurtemre.sonora.shared.queue

import de.yurtemre.sonora.shared.model.QueueState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ShuffleTest {
    private fun queue(n: Int, current: Int = 3): QueueState =
        QueueState().load((1L..n).toList()).copy(currentIndex = current)

    @Test fun shuffleOnUsesReorderWhenGiven() {
        val q = queue(10)
        val s = q.shuffleOn(Random(1)) { it.reversed() }
        assertEquals(q.entries.take(4), s.entries.take(4))
        assertEquals(q.entries.drop(4).reversed(), s.entries.drop(4))
        assertEquals(q.currentEntry, s.currentEntry)
        assertEquals(true, s.isShuffled)
    }

    @Test fun shuffleOnWithoutReorderUnchanged() {
        val q = queue(10)
        val expected = q.entries.take(4) + q.entries.drop(4).shuffled(Random(42))
        assertEquals(expected, q.shuffleOn(Random(42)).entries)
        assertEquals(expected, q.shuffleOn(Random(42), null).entries)
        assertEquals(expected, q.toggleShuffle(Random(42)).entries)
    }

    @Test fun toggleShuffleUsesReorder() {
        val q = queue(6, current = 0)
        assertEquals(q.entries.reversed().take(5), q.toggleShuffle(Random(1)) { it.reversed() }.entries.drop(1))
    }

    @Test fun quickShuffleUsesReorder() {
        val q = QueueState().quickShuffle(listOf(1, 2, 3, 4), Random(1), reorder = { it.reversed() })
        assertEquals(listOf(4L, 3L, 2L, 1L), q.entries.map { it.songId })
        assertEquals(0, q.currentIndex)
        assertEquals(listOf(1L, 2L, 3L, 4L), q.original.map { it.songId })
        assertEquals(true, q.isShuffled)
    }
}
