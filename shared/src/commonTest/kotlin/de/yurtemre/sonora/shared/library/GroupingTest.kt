package de.yurtemre.sonora.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupingTest {
    @Test fun parsesIndividualArtists() {
        assertEquals(listOf("Drake", "Future", "Young Thug"), ArtistParser.parse("Drake feat. Future & Young Thug"))
        assertEquals(listOf("AC/DC"), ArtistParser.parse("AC/DC"))
        assertEquals(listOf("Simon&Garfunkel"), ArtistParser.parse("Simon&Garfunkel"))
        assertEquals(listOf("Simon", "Garfunkel"), ArtistParser.parse("Simon & Garfunkel"))
        assertEquals(listOf("A", "B", "C"), ArtistParser.parse("A\\B;C"))
        assertEquals(listOf("Earth", "Wind", "Fire"), ArtistParser.parse("Earth, Wind & Fire"))
        assertEquals(listOf("Unknown Artist"), ArtistParser.parse("  "))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A x (B) & a"))
    }





}
