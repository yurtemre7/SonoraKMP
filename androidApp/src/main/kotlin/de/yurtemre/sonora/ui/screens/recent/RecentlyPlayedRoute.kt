package de.yurtemre.sonora.ui.screens.recent

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.navigation.StubScreen

/** Stub route; replaced by its feature task. */
@Composable
fun RecentlyPlayedRoute(onBack: () -> Unit) {
    StubScreen(stringResource(R.string.nav_title_recently_played), onBack, subtitle = null)
}
