package de.yurtemre.sonora.ui.screens.settings.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.ui.theme.SleeveShape
import kotlin.math.roundToInt

/*
 * Shared pieces of the style pickers (title style, player style): a selectable card that shows a miniature of the
 * real Now Playing area. The miniature is composed at its design size and drawn scaled down, so it uses the real
 * components (and animates) instead of a screenshot.
 */

internal val PREVIEW_CARD_WIDTH = 156.dp
internal val PREVIEW_COVER = 132.dp
internal val PREVIEW_PAD = 12.dp
internal val PREVIEW_WIDTH = 236.dp
internal val PREVIEW_HEIGHT = 208.dp

@Composable
internal fun PreviewCard(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.width(PREVIEW_CARD_WIDTH).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant),
    ) {
        Column {
            Box(Modifier.clipToBounds().scaledTo(PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_CARD_WIDTH)) { content() }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) cs.primary else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** A flat stand-in for album art. */
@Composable
internal fun PreviewCover(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.clip(SleeveShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(48.dp))
    }
}

/** A seek bar and three transport dots: just enough to read the miniature as a player. */
@Composable
internal fun PreviewControls(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(cs.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(0.4f).height(4.dp).background(cs.primary))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(cs.surfaceContainerHighest))
            Box(Modifier.size(22.dp).clip(CircleShape).background(cs.primary))
            Box(Modifier.size(22.dp).clip(CircleShape).background(cs.surfaceContainerHighest))
        }
    }
}

/** Lays the child out at its design size and draws it scaled to [targetWidth]. */
internal fun Modifier.scaledTo(designWidth: Dp, designHeight: Dp, targetWidth: Dp): Modifier = layout { measurable, _ ->
    val k = targetWidth.toPx() / designWidth.toPx()
    val w = designWidth.roundToPx()
    val h = designHeight.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout((w * k).roundToInt(), (h * k).roundToInt()) {
        placeable.placeWithLayer(0, 0) {
            scaleX = k
            scaleY = k
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}
