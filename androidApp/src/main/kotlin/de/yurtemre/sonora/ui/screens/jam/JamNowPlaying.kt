package de.yurtemre.sonora.ui.screens.jam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.jam.JamUiState

/** The Jam flags for the player controls below Now Playing ([JamPlayerUi.None] outside a Jam). */
val LocalJamPlayerUi = compositionLocalOf { JamPlayerUi.None }

/**
 * The Jam line at the top of Now Playing: "In a Jam with <host>" with a quiet sync status and Leave for a guest;
 * "Hosting a Jam with N guests" for the host; "Jam ended" with the reason and Done once the controller says it is
 * over (for a host that dropped: after the current song). Nothing outside a Jam. Tapping it opens the Jam screen
 * (which offers Rejoin after a Wi-Fi change).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JamBanner(
    ui: JamPlayerUi,
    onOpen: () -> Unit,
    onLeave: () -> Unit,
    onDismissEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ended = ui.ended
    if (ui.role == JamRole.None && ended == null) return
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = cs.secondaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Groups, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                val title = when {
                    ended != null -> stringResource(R.string.jam_ended_title)
                    ui.role == JamRole.Guest -> stringResource(R.string.jam_with, ui.hostName)
                    ui.guestCount == 0 -> stringResource(R.string.jam_hosting)
                    else -> pluralStringResource(R.plurals.jam_hosting_with, ui.guestCount, ui.guestCount)
                }
                Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer)
                val sub = when {
                    ended != null -> stringResource(jamEndMessage(ended.reason))
                    else -> ui.status?.let { stringResource(jamStatusText(it)) }
                }
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
            }
            FlowRow(horizontalArrangement = Arrangement.End) {
                when {
                    ended != null -> TextButton(onClick = onDismissEnded, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.jam_done))
                    }
                    ui.role == JamRole.Guest -> TextButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.jam_leave))
                    }
                    else -> Unit
                }
            }
        }
    }
}

/**
 * Now Playing's "More options": Start a Jam / Join a Jam outside a Jam; Open Jam while hosting; Open Jam and Leave
 * for a guest.
 */
@Composable
fun JamMenu(ui: JamPlayerUi, onStart: () -> Unit, onJoin: () -> Unit, onOpen: () -> Unit, onLeave: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_more_options))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val items = when (ui.role) {
                JamRole.None -> listOf(R.string.jam_start to onStart, R.string.jam_join to onJoin)
                JamRole.Host -> listOf(R.string.jam_open to onOpen)
                JamRole.Guest -> listOf(R.string.jam_open to onOpen, R.string.jam_leave_jam to onLeave)
            }
            items.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; action() })
            }
        }
    }
}

/** A guest's read-only progress (no seeking: the host controls the position), with the reason underneath. */
@Composable
fun JamGuestProgress(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.jam_guest_seek_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The Jam state while a guest is in one, else null (for the guest-only parts of Now Playing). */
fun JamUiState.joinedOrNull(): JamUiState.Joined? = this as? JamUiState.Joined
