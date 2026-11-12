package de.yurtemre.sonora.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import de.yurtemre.sonora.R
import de.yurtemre.sonora.testing.FakePlayback
import de.yurtemre.sonora.testing.startTestKoin
import de.yurtemre.sonora.testing.testSong
import de.yurtemre.sonora.ui.navigation.NowPlayingSlot
import de.yurtemre.sonora.ui.navigation.NowPlayingSlotState
import de.yurtemre.sonora.ui.navigation.rememberNowPlayingSlotState
import de.yurtemre.sonora.ui.theme.SonoraTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Back from Up next returns to the player; back from the player collapses the overlay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NowPlayingBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() = startTestKoin(app, playback = FakePlayback(current = testSong(1, "Now")))
    @After fun tearDown() = stopKoin()

    @Test fun backLeavesUpNextThenCollapses() {
        lateinit var slot: NowPlayingSlotState
        compose.setContent {
            SonoraTheme {
                slot = rememberNowPlayingSlotState()
                NowPlayingSlot(slot)
            }
        }
        compose.runOnIdle { slot.expanded = true }
        val upNext = hasText(app.getString(R.string.np_view_up_next)) and isToggleable()
        compose.onNode(upNext).performClick().assertIsOn()

        back()
        compose.onNode(upNext).assertIsOff()
        compose.runOnIdle { assertTrue(slot.expanded) }

        back()
        compose.runOnIdle { assertFalse(slot.expanded) }
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
