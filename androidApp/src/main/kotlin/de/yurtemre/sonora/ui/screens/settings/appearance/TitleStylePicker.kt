package de.yurtemre.sonora.ui.screens.settings.appearance

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.TitleStyle
import de.yurtemre.sonora.ui.screens.nowplaying.Spine
import de.yurtemre.sonora.ui.screens.nowplaying.rememberSpineFits
import de.yurtemre.sonora.ui.screens.nowplaying.spineWidth
import de.yurtemre.sonora.ui.screens.nowplaying.usesSpine
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.theme.SleeveShape
import kotlin.math.roundToInt

/**
 * Title style setting: one preview card per style, each drawn by the real [Spine] on a miniature Now Playing
 * (scaled down, with a long sample title), so what you pick is what you get. The marquee preview keeps scrolling.
 */
@Composable
internal fun TitleStylePicker(selected: TitleStyle, onSelect: (TitleStyle) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(stringResource(R.string.settings_title_style_title), icon = Icons.Rounded.Title)
        val sample = stringResource(R.string.settings_title_style_sample)
        LazyRow(
            modifier = Modifier.selectableGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(TitleStyle.entries, key = { it.storageName }) { style ->
                TitleStyleCard(style, sample, selected = style == selected, onClick = { onSelect(style) })
            }
        }
        AnimatedContent(selected, label = "titleStyleDesc") { style ->
            SettingsNote(stringResource(style.descRes))
        }
    }
}

@Composable
private fun TitleStyleCard(style: TitleStyle, sample: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.width(CARD_WIDTH).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant),
    ) {
        Column {
            Box(Modifier.clipToBounds().scaledTo(MOCK_WIDTH, MOCK_HEIGHT, CARD_WIDTH)) {
                TitleStyleMock(style, sample)
            }
            Text(
                stringResource(style.labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) cs.primary else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** A miniature of the Now Playing artwork area at design size: the real Spine beside a cover, or the Classic title. */
@Composable
private fun TitleStyleMock(style: TitleStyle, title: String) {
    val cs = MaterialTheme.colorScheme
    val fits = rememberSpineFits(title)
    Column(Modifier.size(MOCK_WIDTH, MOCK_HEIGHT).padding(MOCK_PAD).clearAndSetSemantics { }) {
        Row {
            if (style.usesSpine) {
                Spine(title, Modifier.height(COVER).widthIn(max = style.spineWidth), style = style, loopMarquee = true)
                Spacer(Modifier.width(8.dp))
            }
            Box(
                Modifier.size(COVER).clip(SleeveShape).background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(48.dp))
            }
        }
        val caption = style == TitleStyle.Hybrid && !fits(COVER)
        if (caption || !style.usesSpine) {
            Text(
                title,
                style = if (style.usesSpine) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (style.usesSpine) style.spineWidth + 8.dp else 0.dp, top = 8.dp),
            )
        }
    }
}

/** Lays the child out at its design size and draws it scaled to [targetWidth]. */
private fun Modifier.scaledTo(designWidth: Dp, designHeight: Dp, targetWidth: Dp): Modifier = layout { measurable, _ ->
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

private val CARD_WIDTH = 156.dp
private val COVER = 132.dp
private val MOCK_PAD = 12.dp
private val MOCK_WIDTH = 236.dp
private val MOCK_HEIGHT = 208.dp

internal val TitleStyle.labelRes: Int get() = when (this) {
    TitleStyle.Marquee -> R.string.settings_title_marquee
    TitleStyle.Fit -> R.string.settings_title_fit
    TitleStyle.TwoColumns -> R.string.settings_title_two_columns
    TitleStyle.Hybrid -> R.string.settings_title_hybrid
    TitleStyle.Classic -> R.string.settings_title_classic
}

internal val TitleStyle.descRes: Int get() = when (this) {
    TitleStyle.Marquee -> R.string.settings_title_marquee_desc
    TitleStyle.Fit -> R.string.settings_title_fit_desc
    TitleStyle.TwoColumns -> R.string.settings_title_two_columns_desc
    TitleStyle.Hybrid -> R.string.settings_title_hybrid_desc
    TitleStyle.Classic -> R.string.settings_title_classic_desc
}
