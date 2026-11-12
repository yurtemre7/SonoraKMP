package de.yurtemre.sonora.ui

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.components.MultiSelectActionBar
import de.yurtemre.sonora.ui.components.SelectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MultiSelectBarTest {
    @get:Rule val compose = createComposeRule()
    private val res = ApplicationProvider.getApplicationContext<Application>().resources

    @Test fun countSelectAllActionsAndClose() {
        val selection = SelectionState(setOf(1L))
        var playedNext = emptySet<Long>()
        var favorited: Pair<Set<Long>, Boolean>? = null
        compose.setContent {
            MultiSelectActionBar(
                selection = selection,
                allIds = { listOf(1L, 2L, 3L) },
                snackbarHostState = SnackbarHostState(),
                onPlayNext = { playedNext = it },
                onAddToQueue = {},
                onAddToPlaylist = {},
                allFavorite = false,
                onFavoriteToggle = { ids, fav -> favorited = ids to fav },
                shareItems = { emptyList() },
            )
        }
        fun label(id: Int) = res.getString(id)
        compose.onNodeWithText(res.getQuantityString(R.plurals.selection_count, 1, 1)).assertExists()

        compose.onNodeWithContentDescription(label(R.string.selection_select_all)).performClick()
        compose.onNodeWithText(res.getQuantityString(R.plurals.selection_count, 3, 3)).assertExists()

        compose.onNodeWithContentDescription(label(R.string.selection_favorite)).performClick()
        assertEquals(setOf(1L, 2L, 3L) to true, favorited)

        compose.onNodeWithContentDescription(label(R.string.selection_play_next)).performClick()
        assertEquals(setOf(1L, 2L, 3L), playedNext)
        compose.runOnIdle { assertFalse(selection.active) } // play next exits selection mode

        selection.toggle(2L)
        compose.onNodeWithContentDescription(label(R.string.selection_close)).performClick()
        compose.runOnIdle { assertFalse(selection.active) }
    }
}
