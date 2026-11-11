package de.yurtemre.sonora.ui.screens.nowplaying

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.yurtemre.sonora.shared.model.Song

/**
 * Now Playing as the right pane on expanded widths (UI-34, DESIGN §5/§7): the Spine stands between the library pane
 * and the player (PlayerView draws it on the pane's leading edge, so it is the divider), instead of the full-screen overlay used on compact/medium widths.
 */
@Composable
fun NowPlayingTwoPane(
    onCollapse: () -> Unit,
    onOpenAlbum: (Song) -> Unit,
    onOpenArtist: (Song) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    Row(modifier) {
        NowPlayingOverlay(
            onCollapse = onCollapse,
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            snackbarHostState = snackbarHostState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}
