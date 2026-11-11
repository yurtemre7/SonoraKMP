package de.yurtemre.sonora.ui.screens.settings.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import de.yurtemre.sonora.ui.screens.settings.settingsHeader

/** One card per permission actually declared in the manifest (SET-05). */
private data class PrivacyCard(val key: String, val icon: ImageVector, val title: Int, val body: Int)

private val PermissionCards = listOf(
    // READ_MEDIA_AUDIO (READ_EXTERNAL_STORAGE ≤ API 32)
    PrivacyCard("audio", Icons.Rounded.LibraryMusic, R.string.settings_perm_audio_title, R.string.settings_perm_audio_body),
    // POST_NOTIFICATIONS, FOREGROUND_SERVICE(_MEDIA_PLAYBACK)
    PrivacyCard(
        "notifications", Icons.Rounded.NotificationsActive,
        R.string.settings_perm_notifications_title, R.string.settings_perm_notifications_body,
    ),
    // WAKE_LOCK
    PrivacyCard("wake", Icons.Rounded.BatteryChargingFull, R.string.settings_perm_wake_title, R.string.settings_perm_wake_body),
    // INTERNET, ACCESS_NETWORK_STATE
    PrivacyCard("internet", Icons.Rounded.Public, R.string.settings_perm_internet_title, R.string.settings_perm_internet_body),
    // REQUEST_INSTALL_PACKAGES
    PrivacyCard("install", Icons.Rounded.InstallMobile, R.string.settings_perm_install_title, R.string.settings_perm_install_body),
)

@Composable
fun SettingsPrivacyRoute(onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.settings_privacy_title), onBack) {
        item(key = "data") {
            InfoCard(Icons.Rounded.Security, R.string.settings_privacy_data_title, R.string.settings_privacy_data_body, emphasized = true)
        }
        settingsHeader("permissions") { stringResource(R.string.settings_section_permissions) }
        PermissionCards.forEach { card ->
            item(key = card.key) { InfoCard(card.icon, card.title, card.body) }
        }
        settingsHeader("your_data") { stringResource(R.string.settings_section_data) }
        item(key = "delete") {
            InfoCard(Icons.Rounded.DeleteSweep, R.string.settings_privacy_delete_title, R.string.settings_privacy_delete_body)
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: Int, body: Int, emphasized: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        color = if (emphasized) cs.primaryContainer else cs.surfaceContainerLow,
        contentColor = if (emphasized) cs.onPrimaryContainer else cs.onSurface,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = if (emphasized) cs.onPrimaryContainer else cs.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (emphasized) cs.onPrimaryContainer else cs.onSurfaceVariant,
                )
            }
        }
    }
}
