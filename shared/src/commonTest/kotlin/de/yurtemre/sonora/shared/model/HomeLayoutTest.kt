package de.yurtemre.sonora.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeLayoutTest {
    @Test fun emptyOrMissingIsTheDefault() {
        assertEquals(HomeLayout.default, HomeLayout.parse(null))
        assertEquals(HomeLayout.default, HomeLayout.parse(""))
        assertEquals(HomeLayout.default, HomeLayout.parse("garbage"))
    }

    @Test fun defaultPutsJumpBackFirstAndShuffleBelowIt() {
        val order = HomeLayout.default.map { it.section }
        assertEquals(HomeSection.JumpBack, order.first())
        assertEquals(true, order.indexOf(HomeSection.Actions) > order.indexOf(HomeSection.JumpBack))
    }

    @Test fun roundTripKeepsOrderAndVisibility() {
        val layout = HomeLayout.default.reversed().mapIndexed { i, s -> s.copy(visible = i % 3 != 0) }
        assertEquals(layout, HomeLayout.parse(HomeLayout.serialize(layout)))
    }

    @Test fun unknownAndRepeatedKeysAreDroppedAndMissingOnesAppended() {
        val parsed = HomeLayout.parse("recent:0,nope:1,recent:1,glance:1")
        assertEquals(HomeSection.Recent to false, parsed[0].section to parsed[0].visible)
        assertEquals(HomeSection.Glance, parsed[1].section)
        assertEquals(HomeSection.entries.size, parsed.size)
        assertEquals(HomeSection.entries.toSet(), parsed.map { it.section }.toSet())
        assertEquals(true, parsed.drop(2).all { it.visible })
    }
}
