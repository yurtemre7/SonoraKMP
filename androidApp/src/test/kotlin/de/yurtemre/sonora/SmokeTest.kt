package de.yurtemre.sonora

import android.app.Application
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import de.yurtemre.sonora.permissions.MediaPermissions
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.testing.startTestKoin
import de.yurtemre.sonora.testing.testSong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Launches the real MainActivity on fakes: fresh install → onboarding; onboarded + library → Songs tab lists songs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After fun tearDown() = stopKoin()

    @Test fun launchesIntoOnboardingThenLibrary() {
        startTestKoin(app)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(app.getString(R.string.onboarding_welcome_title)).fetchSemanticsNodes().isNotEmpty() }
        }

        startTestKoin(app, songs = listOf(testSong(1, "First light"), testSong(2, "Second wind")))
        runBlocking {
            val settings = GlobalContext.get().get<SettingsRepository>()
            settings.setScanFolder("/music", null)
            settings.setOnboardingCompleted(true)
        }
        shadowOf(app).grantPermissions(MediaPermissions.audio)
        ActivityScenario.launch(MainActivity::class.java).use {
            // Song rows merge their text into one description ("<title> by <artist>, <duration>").
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("First light", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasContentDescription("Second wind", substring = true)).assertExists()
        }
    }
}
