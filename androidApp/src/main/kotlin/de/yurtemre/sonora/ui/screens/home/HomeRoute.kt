package de.yurtemre.sonora.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.labelRes
import de.yurtemre.sonora.ui.navigation.StubScreen

/** Stub Home (library) route; replaced by P5-03/P5-04. */
@Composable
fun HomeRoute(tab: LibraryTab, onNavigate: (androidx.navigation3.runtime.NavKey) -> Unit) {
    StubScreen(stringResource(R.string.nav_title_library), onBack = null, subtitle = stringResource(tab.labelRes))
}
