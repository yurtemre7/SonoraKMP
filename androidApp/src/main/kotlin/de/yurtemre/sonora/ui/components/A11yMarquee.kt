package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import de.yurtemre.sonora.ui.theme.LocalReduceMotion

/** A11Y: marquee only when motion is allowed and font scale < 1.3 (large text wraps/ellipsizes instead). */
@Composable
fun Modifier.a11yMarquee(): Modifier {
    val allowed = !LocalReduceMotion.current && LocalDensity.current.fontScale < 1.3f
    return if (allowed) this.basicMarquee() else this
}
