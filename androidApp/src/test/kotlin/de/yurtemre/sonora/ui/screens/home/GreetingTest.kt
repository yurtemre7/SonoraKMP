package de.yurtemre.sonora.ui.screens.home

import de.yurtemre.sonora.R
import org.junit.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test fun greetingFollowsTheHourIncludingNight() {
        val expected = mapOf(
            0 to R.string.good_night, 4 to R.string.good_night,
            5 to R.string.good_morning, 11 to R.string.good_morning,
            12 to R.string.good_afternoon, 16 to R.string.good_afternoon,
            17 to R.string.good_evening, 21 to R.string.good_evening,
            22 to R.string.good_night, 23 to R.string.good_night,
        )
        for ((hour, res) in expected) assertEquals(res, greetingRes(hour), "hour $hour")
    }
}
