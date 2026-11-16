package de.yurtemre.sonora.ui.screens.settings.appearance

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.TitleStyle
import de.yurtemre.sonora.ui.screens.nowplaying.Spine
import de.yurtemre.sonora.ui.screens.nowplaying.rememberSpineFits
import de.yurtemre.sonora.ui.screens.nowplaying.spineWidth
import de.yurtemre.sonora.ui.screens.nowplaying.usesSpine
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsRow

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
                PreviewCard(stringResource(style.labelRes), selected = style == selected, onClick = { onSelect(style) }) {
                    TitleStyleMock(style, sample)
                }
            }
        }
        AnimatedContent(selected, label = "titleStyleDesc") { style ->
            SettingsNote(stringResource(style.descRes))
        }
    }
}

/** A miniature of the Now Playing artwork area at design size: the real Spine beside a cover, or the Classic title. */
@Composable
private fun TitleStyleMock(style: TitleStyle, title: String) {
    val cs = MaterialTheme.colorScheme
    val fits = rememberSpineFits(title)
    Column(Modifier.size(PREVIEW_WIDTH, PREVIEW_HEIGHT).padding(PREVIEW_PAD).clearAndSetSemantics { }) {
        Row {
            if (style.usesSpine) {
                Spine(title, Modifier.height(PREVIEW_COVER).widthIn(max = style.spineWidth), style = style, loopMarquee = true)
                Spacer(Modifier.width(8.dp))
            }
            PreviewCover(Modifier.size(PREVIEW_COVER))
        }
        val caption = style == TitleStyle.Hybrid && !fits(PREVIEW_COVER)
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
