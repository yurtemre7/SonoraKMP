package de.yurtemre.sonora.ui.screens.jam

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.jam.JamController
import de.yurtemre.sonora.jam.JamPlaybackGuard
import de.yurtemre.sonora.jam.JamUiState
import org.koin.compose.koinInject

/**
 * "Leave the Jam to play your own music?" for a guest's held own playback ([JamPlaybackGuard.pending]): Leave and
 * play, or Stay; for play next / add to queue while the host allows adds, "Add to the Jam queue" comes first.
 * Placed once in the app shell; [onSentToJam] confirms an add that went to the host.
 */
@Composable
fun JamOwnPlaybackDialog(onSentToJam: (sent: Int) -> Unit = {}) {
    val guard: JamPlaybackGuard = koinInject()
    val controller: JamController = koinInject()
    val pending by guard.pending.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    val p = pending ?: return
    val joined = state as? JamUiState.Joined
    val addable = guard.canAddToJam(p)
    AlertDialog(
        onDismissRequest = guard::stay,
        title = { Text(stringResource(R.string.jam_own_title)) },
        text = {
            Text(
                if (addable) stringResource(R.string.jam_own_body_add)
                else stringResource(R.string.jam_own_body, joined?.hostName.orEmpty()),
            )
        },
        confirmButton = {
            if (addable) TextButton(onClick = { onSentToJam(guard.addToJam()) }) { Text(stringResource(R.string.jam_own_add)) }
            else TextButton(onClick = guard::leaveAndPlay) { Text(stringResource(R.string.jam_own_leave)) }
        },
        dismissButton = {
            Row {
                if (addable) TextButton(onClick = guard::leaveAndPlay) { Text(stringResource(R.string.jam_own_leave)) }
                TextButton(onClick = guard::stay) { Text(stringResource(R.string.jam_own_stay)) }
            }
        },
    )
}
