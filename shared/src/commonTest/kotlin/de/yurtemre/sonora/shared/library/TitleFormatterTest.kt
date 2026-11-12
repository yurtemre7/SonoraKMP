package de.yurtemre.sonora.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals

class TitleFormatterTest {
    @Test
    fun stripsArtistPrefixAndSuffixWithDashOrTildeCaseInsensitively() {
        assertEquals(
            "On Possession",
            TitleFormatter.displayTitle("Sea Oleena ~ On Possession", "sea oleena", filterFeatures = true, filterArtist = true),
        )
        assertEquals(
            "On Possession",
            TitleFormatter.displayTitle("On Possession ~ Sea Oleena", "sea oleena", filterFeatures = true, filterArtist = true),
        )
        assertEquals(
            "Song",
            TitleFormatter.displayTitle("Artist - Song", "artist", filterFeatures = false, filterArtist = true),
        )
        assertEquals(
            "Song",
            TitleFormatter.displayTitle("Song — Artist", "Artist", filterFeatures = false, filterArtist = true),
        )
    }

    @Test
    fun preservesTitleWhenFilterArtistDisabled() {
        assertEquals(
            "Sea Oleena ~ On Possession",
            TitleFormatter.displayTitle("Sea Oleena ~ On Possession", "sea oleena", filterFeatures = true, filterArtist = false),
        )
    }

    @Test
    fun stripsVideoTagsWhenEnabled() {
        assertEquals(
            "EYE TO EYE",
            TitleFormatter.displayTitle(
                "EYE TO EYE (MUSIC VIDEO)",
                "yaego",
                filterFeatures = false,
                filterArtist = false,
                filterVideoTags = true,
            ),
        )
        assertEquals(
            "Just Your Doll",
            TitleFormatter.displayTitle(
                "Just Your Doll (Official Video)",
                "Snow Strippers",
                filterFeatures = false,
                filterArtist = false,
                filterVideoTags = true,
            ),
        )
        assertEquals(
            "Track",
            TitleFormatter.displayTitle(
                "Track [Official Music Video]",
                "Artist",
                filterFeatures = false,
                filterArtist = false,
                filterVideoTags = true,
            ),
        )
        assertEquals(
            "Track",
            TitleFormatter.displayTitle(
                "Track (Official Audio)",
                "Artist",
                filterFeatures = false,
                filterArtist = false,
                filterVideoTags = true,
            ),
        )
        assertEquals(
            "EYE TO EYE (MUSIC VIDEO)",
            TitleFormatter.displayTitle(
                "EYE TO EYE (MUSIC VIDEO)",
                "yaego",
                filterFeatures = false,
                filterArtist = false,
                filterVideoTags = false,
            ),
        )
    }
}
