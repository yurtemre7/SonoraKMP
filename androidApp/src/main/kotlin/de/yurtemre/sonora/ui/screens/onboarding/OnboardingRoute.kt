package de.yurtemre.sonora.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.navigation.StubScreen

/** Stub route; replaced by its feature task. */
@Composable
fun OnboardingRoute(onBack: () -> Unit) {
    StubScreen(stringResource(R.string.nav_title_onboarding), onBack, subtitle = null)
}
