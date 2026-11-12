package de.yurtemre.sonora.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Fraction the backdrop is tinted toward the cover's dominant colour (DESIGN §6.3). */
const val BACKDROP_TINT = 0.12f

/** Opacity of the flat surface laid over the blurred sleeve (DESIGN §6.3). */
const val BLUR_OVERLAY_ALPHA = 0.88f

/** Whether the device can draw the opt-in blurred backdrop (RenderEffect, API 31+). */
val BlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Now Playing backdrop (DESIGN §6.3): surfaceContainerLow tinted 12% toward [dominant]. With [blurModel] set (the
 * "Blurred backgrounds" setting) and API 31+, the sleeve is drawn blurred underneath and the tint becomes a flat
 * 88% overlay; otherwise it stays one flat colour.
 */
@Composable
fun TintedBackdrop(
    dominant: Color?,
    modifier: Modifier = Modifier,
    blurModel: Any? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val target = if (dominant == null) base else lerp(base, dominant, BACKDROP_TINT)
    val color by animateColorAsState(target, MaterialTheme.motionScheme.slowEffectsSpec(), label = "backdrop")
    if (blurModel == null || !BlurSupported) {
        Box(modifier.background(color), content = content)
        return
    }
    Box(modifier.background(base).clipToBounds()) {
        AsyncImage(
            model = blurModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(60.dp),
        )
        Box(Modifier.fillMaxSize().background(color.copy(alpha = BLUR_OVERLAY_ALPHA)), content = content)
    }
}
