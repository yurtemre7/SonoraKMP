package de.yurtemre.sonora.ui.screens.jam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.ui.screens.settings.SwitchRow

/**
 * Choose what guests may do, then start: "Guests can add songs" (default on), "Guests can skip and pause"
 * (default off). [onStart] gets the choice; the caller starts the Jam (and plays a queue first, if it has one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamStartSheet(onDismiss: () -> Unit, onStart: (JamSettings) -> Unit) {
    val defaults = JamSettings()
    var canAdd by rememberSaveable { mutableStateOf(defaults.guestsCanAdd) }
    var canControl by rememberSaveable { mutableStateOf(defaults.guestsCanControl) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.jam_start),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.jam_start_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            JamPermissionRows(canAdd, { canAdd = it }, canControl, { canControl = it })
            Button(
                onClick = { onStart(JamSettings(guestsCanAdd = canAdd, guestsCanControl = canControl)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.jam_start_button)) }
        }
    }
}

/** The two guest permissions as switch rows (start sheet and host screen). */
@Composable
internal fun JamPermissionRows(
    canAdd: Boolean,
    onCanAdd: (Boolean) -> Unit,
    canControl: Boolean,
    onCanControl: (Boolean) -> Unit,
) {
    Column {
        SwitchRow(
            stringResource(R.string.jam_perm_add), canAdd, onCanAdd,
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd, subtitle = stringResource(R.string.jam_perm_add_subtitle),
        )
        SwitchRow(
            stringResource(R.string.jam_perm_control), canControl, onCanControl,
            icon = Icons.Rounded.SkipNext, subtitle = stringResource(R.string.jam_perm_control_subtitle),
        )
    }
}
