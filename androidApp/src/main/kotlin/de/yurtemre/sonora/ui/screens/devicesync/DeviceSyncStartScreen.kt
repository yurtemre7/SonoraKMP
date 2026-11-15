package de.yurtemre.sonora.ui.screens.devicesync

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold

/** DSYNC-01: pick the role. */
@Composable
internal fun DeviceSyncStartScreen(onSend: () -> Unit, onReceive: () -> Unit, onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.settings_devicesync_title), onBack) {
        item(key = "intro") { SettingsNote(stringResource(R.string.devicesync_intro)) }
        item(key = "send") {
            ChoiceCard(
                Icons.Rounded.Upload,
                stringResource(R.string.devicesync_send_title),
                stringResource(R.string.devicesync_send_body),
                onSend,
            )
        }
        item(key = "receive") {
            ChoiceCard(
                Icons.Rounded.Download,
                stringResource(R.string.devicesync_receive_title),
                stringResource(R.string.devicesync_receive_body),
                onReceive,
            )
        }
    }
}
