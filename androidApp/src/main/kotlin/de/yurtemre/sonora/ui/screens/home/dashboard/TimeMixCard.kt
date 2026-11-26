package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.TimeOfDayMix
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import de.yurtemre.sonora.ui.theme.SleeveRowShape

private fun TimeOfDayMix.Slot.titleRes(): Int = when (this) {
    TimeOfDayMix.Slot.Morning -> R.string.home_mix_morning
    TimeOfDayMix.Slot.Afternoon -> R.string.home_mix_afternoon
    TimeOfDayMix.Slot.Evening -> R.string.home_mix_evening
    TimeOfDayMix.Slot.Night -> R.string.home_mix_night
}

/** "Good morning mix" and friends: cover collage, title, song count and a play button; the whole card plays too. */
@Composable
fun TimeMixCard(mix: TimeMixUi, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "timeMixScale",
    )
    val play = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onPlay()
    }
    val covers = mix.songs.distinctBy { it.artworkPath ?: it.album }.take(4)
    Surface(
        onClick = play,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).scale(scale),
        shape = SleeveRowShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MixCollage(covers.map { it.artworkPath to it.album })
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(mix.slot.titleRes()),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.song_count, mix.songs.size, mix.songs.size),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
            FilledIconButton(onClick = play) {
                Icon(Icons.Rounded.PlayArrow, stringResource(R.string.home_mix_play_cd, stringResource(mix.slot.titleRes())))
            }
        }
    }
}

/** Up to four covers as a flat 2x2 grid (fewer fill the tile with the first cover). */
@Composable
private fun MixCollage(covers: List<Pair<String?, String>>) {
    val tile = 72.dp
    if (covers.size < 4) {
        AlbumArt(covers.firstOrNull()?.first, covers.firstOrNull()?.second ?: "", Modifier.size(tile), shape = SleeveRowShape, decorative = true)
        return
    }
    Column(Modifier.size(tile)) {
        covers.chunked(2).forEach { row ->
            Row(Modifier.weight(1f)) {
                row.forEach { (art, album) ->
                    AlbumArt(art, album, Modifier.weight(1f).fillMaxHeight(), shape = SleeveRowShape, decorative = true)
                }
            }
        }
    }
}
