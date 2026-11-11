package de.yurtemre.sonora.ui.screens.update

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.UpdateChannel
import de.yurtemre.sonora.shared.update.UpdateFailure
import de.yurtemre.sonora.ui.format.DateFormats
import de.yurtemre.sonora.ui.format.currentLocale
import de.yurtemre.sonora.update.UpdateUiState
import de.yurtemre.sonora.update.UpdateViewModel
import de.yurtemre.sonora.update.rememberUpdateViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Settings › Info "Check for updates" row (UPD-07): checking / up to date / available / failed (incl. rate-limit reset
 * time, no network). Tapping checks, or opens the sheet when an update is known; rate limits open a dialog.
 */
@Composable
fun UpdateCheckTile(modifier: Modifier = Modifier, viewModel: UpdateViewModel = rememberUpdateViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastCheck by viewModel.lastCheckMs.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var rateLimitReset by remember { mutableStateOf<Long?>(null) }

    val supporting: String = when (val s = state) {
        UpdateUiState.Idle -> if (lastCheck > 0) {
            stringResource(R.string.update_state_last_checked, formatWhen(lastCheck))
        } else stringResource(R.string.update_state_idle)
        UpdateUiState.Checking -> stringResource(R.string.update_state_checking)
        UpdateUiState.UpToDate -> stringResource(R.string.update_state_up_to_date)
        is UpdateUiState.Available -> stringResource(R.string.update_state_available, s.update.version.toString())
        is UpdateUiState.Failed -> failureText(s.reason)
    }
    val icon = when (state) {
        UpdateUiState.UpToDate -> Icons.Rounded.CheckCircle
        is UpdateUiState.Available -> Icons.Rounded.NewReleases
        is UpdateUiState.Failed -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.SystemUpdate
    }
    val cs = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(stringResource(R.string.check_for_updates)) },
        supportingContent = { Text(supporting) },
        leadingContent = {
            Icon(
                icon, null,
                tint = when (state) {
                    is UpdateUiState.Available -> cs.primary
                    is UpdateUiState.Failed -> cs.error
                    else -> cs.onSurfaceVariant
                },
            )
        },
        trailingContent = { if (state == UpdateUiState.Checking) LoadingIndicator(Modifier.size(32.dp)) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = modifier.semantics(mergeDescendants = true) { role = Role.Button }.then(
            Modifier.clickable(enabled = state != UpdateUiState.Checking) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                when (val s = state) {
                    is UpdateUiState.Available -> viewModel.showSheet()
                    is UpdateUiState.Failed -> {
                        val r = s.reason
                        if (r is UpdateFailure.RateLimited && r.resetAtMs > System.currentTimeMillis()) {
                            rateLimitReset = r.resetAtMs
                        } else viewModel.checkManually()
                    }
                    else -> viewModel.checkManually()
                }
            },
        ),
    )
    rateLimitReset?.let { reset -> RateLimitDialog(reset) { rateLimitReset = null } }
}


/** Rate-limit dialog with the localized reset time and a link to the releases page (§7). */
@Composable
fun RateLimitDialog(resetAtMs: Long, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.WarningAmber, null) },
        title = { Text(stringResource(R.string.update_rate_limit_title)) },
        text = { Text(stringResource(R.string.update_rate_limit_message, formatWhen(resetAtMs))) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); uri.openUri(UpdateViewModel.releasesUrl) }) {
                Text(stringResource(R.string.update_action_browser))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.info2_close)) } },
    )
}

@Composable
private fun failureText(reason: UpdateFailure): String = when (reason) {
    UpdateFailure.NoNetwork -> stringResource(R.string.update_error_no_network)
    is UpdateFailure.RateLimited -> stringResource(R.string.update_error_rate_limited, formatWhen(reason.resetAtMs))
    is UpdateFailure.Server -> stringResource(R.string.update_error_server, reason.code)
    UpdateFailure.NoAsset -> stringResource(R.string.update_error_no_asset)
    UpdateFailure.Malformed -> stringResource(R.string.update_error_malformed)
    is UpdateFailure.Unknown -> stringResource(R.string.update_error_unknown)
}

/** Time only when today, else date + time, in the user's locale and 12/24 h preference. */
@Composable
private fun formatWhen(ms: Long): String {
    val locale = currentLocale()
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    val instant = Instant.ofEpochMilli(ms)
    val zone = ZoneId.systemDefault()
    return if (instant.atZone(zone).toLocalDate() == LocalDate.now(zone)) {
        DateFormats.time(instant, locale, is24h)
    } else DateFormats.dateTime(instant, locale)
}

/**
 * Update channel picker (UPD-03, D-65): connected toggle buttons Stable / Pre-release / Nightly, the selected
 * channel's description, and a short warning when Nightly is chosen.
 */
@Composable
fun UpdateChannelPicker(modifier: Modifier = Modifier, viewModel: UpdateViewModel = rememberUpdateViewModel()) {
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val options = listOf(
        UpdateChannel.Stable to R.string.update_channel_stable,
        UpdateChannel.PreRelease to R.string.update_channel_prerelease,
        UpdateChannel.Nightly to R.string.update_channel_nightly,
    )
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.update_channel_title), style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            options.forEachIndexed { i, (value, label) ->
                ToggleButton(
                    checked = channel == value,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        viewModel.setChannel(value)
                    },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
        Text(
            stringResource(
                when (channel) {
                    UpdateChannel.Stable -> R.string.update_channel_stable_desc
                    UpdateChannel.PreRelease -> R.string.update_channel_prerelease_desc
                    UpdateChannel.Nightly -> R.string.update_channel_nightly_desc
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        AnimatedVisibility(channel == UpdateChannel.Nightly) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, stringResource(R.string.update_cd_warning), Modifier.padding(end = 12.dp))
                    Text(stringResource(R.string.update_channel_nightly_warning), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
