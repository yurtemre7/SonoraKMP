package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlayerUiState
import de.yurtemre.sonora.playback.api.QueueUiState
import de.yurtemre.sonora.shared.model.RepeatMode
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.PulseButton

/** Which pane the overlay shows (UI-17). */
enum class NowPlayingView { Player, UpNext, Lyrics, Related }

/** Callbacks for the player view; MFX and sleep timer open sheets later. */
class PlayerActions(
    val onTogglePlayPause: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onToggleShuffle: () -> Unit,
    val onCycleRepeat: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onToggleImmersive: () -> Unit,
    val onOpenAlbum: (Song) -> Unit,
    val onOpenArtist: (Song) -> Unit,
    val onMfx: () -> Unit,
    val onSleepTimer: () -> Unit,
)

/** Modern layout (UI-16): Spine | sleeve + chips, seek bar, transport, secondary row, view switcher. */
@Composable
fun PlayerView(
    song: Song,
    state: PlayerUiState,
    isFavorite: Boolean,
    immersive: Boolean,
    view: NowPlayingView,
    onViewChange: (NowPlayingView) -> Unit,
    actions: PlayerActions,
    seekBar: @Composable () -> Unit,
    upNext: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    lyrics: @Composable () -> Unit = {},
    related: @Composable () -> Unit = {},
    queue: QueueUiState? = null,
    onSkipTo: (Long) -> Unit = {},
) {
    val horizontalSpine = spineIsHorizontal(LocalDensity.current.fontScale)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
    if (maxWidth > maxHeight && view == NowPlayingView.Player) {
        // Landscape: the sleeve takes the full safe height on the left, controls on the right, so nothing overflows
        // the safe area (the portrait column would push the square sleeve past the bottom edge).
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spine(song.title, Modifier.fillMaxHeight().widthIn(max = 72.dp))
            Spacer(Modifier.width(8.dp))
            ArtworkArea(
                song, immersive, actions.onToggleImmersive,
                Modifier.fillMaxHeight().padding(bottom = 16.dp).aspectRatio(1f, matchHeightConstraintsFirst = true),
                queue, onSkipTo,
            )
            Spacer(Modifier.width(24.dp))
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                if (!immersive) MetaChips(song, actions, horizontal = true)
                seekBar()
                Transport(state, actions)
                if (!immersive) {
                    SecondaryRow(state, isFavorite, actions)
                    ViewSwitcher(view, onViewChange)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        return@BoxWithConstraints
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (view != NowPlayingView.Player) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                when (view) {
                    NowPlayingView.UpNext -> upNext()
                    NowPlayingView.Lyrics -> lyrics()
                    NowPlayingView.Related -> related()
                    NowPlayingView.Player -> Unit
                }
            }
        } else if (horizontalSpine) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spine(song.title)
                ArtworkArea(
                    song, immersive, actions.onToggleImmersive,
                    // Square, but never taller than the slot left after Spine + chips, so it can't overlap them.
                    Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true),
                    queue, onSkipTo,
                )
                if (!immersive) MetaChips(song, actions)
            }
        } else {
            // Spine runs alongside the sleeve from the same top edge; the sleeve takes the width left by the spine
            // (capped by the slot height minus the chips), and the spare height is split above and below the block.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val chips = if (immersive) 0.dp else 124.dp
                val art = minOf(maxWidth - 80.dp, maxHeight - chips).coerceAtLeast(0.dp)
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().height(art)) {
                        Spine(song.title, Modifier.fillMaxHeight().widthIn(max = 72.dp))
                        Spacer(Modifier.width(8.dp))
                        ArtworkArea(song, immersive, actions.onToggleImmersive, Modifier.size(art), queue = queue, onSkipTo = onSkipTo)
                    }
                    if (!immersive) {
                        Spacer(Modifier.height(12.dp))
                        Row {
                            Spacer(Modifier.width(80.dp))
                            MetaChips(song, actions)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        seekBar()
        Transport(state, actions)
        if (!immersive) {
            SecondaryRow(state, isFavorite, actions)
            ViewSwitcher(view, onViewChange)
        }
        Spacer(Modifier.height(16.dp))
    }
    }
}

@Composable
private fun MetaChips(song: Song, actions: PlayerActions, horizontal: Boolean = false) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val container: @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (horizontal) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
    container {
        val artistLabel = stringResource(R.string.np_open_artist, song.artist)
        AssistChip(
            onClick = { actions.onOpenArtist(song) },
            label = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Person, null) },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = artistLabel },
        )
        val albumLabel = stringResource(R.string.np_open_album, song.album)
        AssistChip(
            onClick = { actions.onOpenAlbum(song) },
            label = { Text(song.album, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Album, null) },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = albumLabel },
        )
    }
}

@Composable
private fun Transport(state: PlayerUiState, actions: PlayerActions) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onPrevious, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.np_previous), Modifier.size(32.dp))
        }
        PulseButton(isPlaying = state.isPlaying, onClick = actions.onTogglePlayPause, size = 80.dp, isCompleted = state.isCompleted)
        IconButton(onClick = actions.onNext, enabled = state.hasNext || state.repeat != RepeatMode.Off, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipNext, stringResource(R.string.np_next), Modifier.size(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SecondaryRow(state: PlayerUiState, isFavorite: Boolean, actions: PlayerActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Connected button group (DESIGN §6.7): shuffle | repeat.
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            val shuffleState = stringResource(if (state.isShuffled) R.string.np_state_on else R.string.np_state_off)
            ToggleButton(
                checked = state.isShuffled,
                onCheckedChange = { actions.onToggleShuffle() },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = shuffleState },
            ) { Icon(Icons.Filled.Shuffle, stringResource(R.string.np_shuffle)) }
            val repeatState = stringResource(
                when (state.repeat) {
                    RepeatMode.Off -> R.string.np_repeat_off
                    RepeatMode.All -> R.string.np_repeat_all
                    RepeatMode.One -> R.string.np_repeat_one
                },
            )
            ToggleButton(
                checked = state.repeat != RepeatMode.Off,
                onCheckedChange = { actions.onCycleRepeat() },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    role = Role.Button
                    stateDescription = repeatState
                },
            ) {
                Icon(
                    if (state.repeat == RepeatMode.One) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    stringResource(R.string.np_repeat),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconToggleButton(checked = isFavorite, onCheckedChange = { actions.onToggleFavorite() }) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                stringResource(R.string.np_favorite),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = actions.onMfx) { Icon(Icons.Filled.GraphicEq, stringResource(R.string.np_mfx)) }
        IconButton(onClick = actions.onSleepTimer) { Icon(Icons.Filled.Bedtime, stringResource(R.string.np_sleep_timer)) }
    }
}

/** View switcher (DESIGN §6.7): connected button group Up next | Lyrics | Related; re-tapping returns to the player. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewSwitcher(view: NowPlayingView, onViewChange: (NowPlayingView) -> Unit) {
    val items = listOf(
        Triple(NowPlayingView.UpNext, Icons.AutoMirrored.Filled.QueueMusic, R.string.np_view_up_next),
        Triple(NowPlayingView.Lyrics, Icons.Filled.Lyrics, R.string.np_view_lyrics),
        Triple(NowPlayingView.Related, Icons.Filled.LibraryMusic, R.string.np_view_related),
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        items.forEachIndexed { i, (target, icon, label) ->
            ToggleButton(
                checked = view == target,
                onCheckedChange = { onViewChange(if (it) target else NowPlayingView.Player) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { role = Role.Tab },
            ) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
