package de.yurtemre.sonora.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import de.yurtemre.sonora.testing.FakePlayback
import de.yurtemre.sonora.testing.startTestKoin
import de.yurtemre.sonora.testing.testSong
import de.yurtemre.sonora.ui.navigation.Library
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.NowPlayingSlotState
import de.yurtemre.sonora.ui.navigation.Settings
import de.yurtemre.sonora.ui.navigation.SonoraAppShell
import de.yurtemre.sonora.ui.navigation.rememberNowPlayingSlotState
import de.yurtemre.sonora.ui.theme.SonoraTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Maintainer report (P5-31): with Settings open and the player expanded, back collapses the player first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackOrderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() = startTestKoin(app, playback = FakePlayback(current = testSong(1, "Now")))
    @After fun tearDown() = stopKoin()

    @Test fun backCollapsesPlayerBeforePoppingSettings() {
        lateinit var stack: NavBackStack<NavKey>
        lateinit var slot: NowPlayingSlotState
        compose.setContent {
            SonoraTheme {
                stack = rememberNavBackStack(Library)
                slot = rememberNowPlayingSlotState()
                SonoraAppShell(WindowWidthSizeClass.Compact, LibraryTab.entries.first(), backStack = stack, nowPlaying = slot)
            }
        }
        compose.runOnIdle { stack.add(Settings) }
        compose.runOnIdle { slot.expanded = true }

        back()
        compose.runOnIdle { assertFalse(slot.expanded); assertEquals(Settings, stack.last()) }

        back()
        compose.runOnIdle { assertEquals(1, stack.size); assertTrue(stack.last() == Library) }
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }
}
