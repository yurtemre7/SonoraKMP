package de.yurtemre.sonora.ui.screens.settings.appearance

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.navigation.StubScreen

/** Stub route; replaced by its feature task. */
@Composable
fun SettingsAppearanceRoute(onBack: () -> Unit) {
    StubScreen(stringResource(R.string.nav_title_settings_appearance), onBack, subtitle = null)
}
