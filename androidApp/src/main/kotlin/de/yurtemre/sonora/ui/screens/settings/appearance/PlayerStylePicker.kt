package de.yurtemre.sonora.ui.screens.settings.appearance

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArtTrack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.NowPlayingStyle
import de.yurtemre.sonora.shared.model.TitleStyle
import de.yurtemre.sonora.ui.screens.nowplaying.Record
import de.yurtemre.sonora.ui.screens.nowplaying.Spine
import de.yurtemre.sonora.ui.screens.nowplaying.spineWidth
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.theme.SleeveShape

/**
 * Player style setting: a preview card per style (a miniature of the real layout, the Vinyl record really turns),
 * and a line saying what the selected one is.
 */
@Composable
internal fun PlayerStylePicker(selected: NowPlayingStyle, onSelect: (NowPlayingStyle) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(stringResource(R.string.settings_np_style_title), icon = Icons.Rounded.ArtTrack)
        val sample = stringResource(R.string.settings_player_style_sample)
        val artist = stringResource(R.string.settings_player_style_artist)
        LazyRow(
            modifier = Modifier.selectableGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(NowPlayingStyle.entries, key = { it.storageName }) { style ->
                PreviewCard(stringResource(style.labelRes), selected = style == selected, onClick = { onSelect(style) }) {
                    PlayerStyleMock(style, sample, artist)
                }
            }
        }
        AnimatedContent(selected, label = "playerStyleDesc") { style ->
            SettingsNote(stringResource(style.descRes))
        }
    }
}

@Composable
private fun PlayerStyleMock(style: NowPlayingStyle, title: String, artist: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.size(PREVIEW_WIDTH, PREVIEW_HEIGHT).padding(PREVIEW_PAD).clearAndSetSemantics { },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.height(PREVIEW_COVER)) {
            Spine(title, Modifier.height(PREVIEW_COVER).widthIn(max = TitleStyle.Hybrid.spineWidth), style = TitleStyle.Hybrid)
            Spacer(Modifier.width(8.dp))
            when (style) {
                NowPlayingStyle.Modern -> PreviewCover(Modifier.size(PREVIEW_COVER))
                NowPlayingStyle.Vinyl -> {
                    // The sleeve slides half out to the left of a turning record, as in the real Vinyl layout.
                    val side = PREVIEW_COVER * 0.7f
                    Box(Modifier.size(PREVIEW_COVER)) {
                        Record(cs.primary, isPlaying = true, modifier = Modifier.align(Alignment.CenterStart).offset(x = PREVIEW_COVER - side).size(side))
                        PreviewCover(Modifier.align(Alignment.CenterStart).size(side))
                    }
                }
                // No artwork at all: the time takes its place, big, with the artist under it.
                NowPlayingStyle.Minimalist -> Column(Modifier.height(PREVIEW_COVER), verticalArrangement = Arrangement.Bottom) {
                    Text("2:41", style = MaterialTheme.typography.displayMedium, color = cs.onSurface)
                    Text(artist, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        PreviewControls(Modifier.padding(top = 8.dp))
    }
}

internal val NowPlayingStyle.labelRes: Int get() = when (this) {
    NowPlayingStyle.Modern -> R.string.settings_np_style_modern
    NowPlayingStyle.Vinyl -> R.string.settings_np_style_vinyl
    NowPlayingStyle.Minimalist -> R.string.settings_np_style_minimalist
}

internal val NowPlayingStyle.descRes: Int get() = when (this) {
    NowPlayingStyle.Modern -> R.string.settings_np_style_modern_desc
    NowPlayingStyle.Vinyl -> R.string.settings_np_style_vinyl_desc
    NowPlayingStyle.Minimalist -> R.string.settings_np_style_minimalist_desc
}
