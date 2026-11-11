package de.yurtemre.sonora.ui.screens.settings.info

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import de.yurtemre.sonora.BuildConfig
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.AppWiper
import de.yurtemre.sonora.platform.UrlOpener
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.components.ConfirmDialog
import de.yurtemre.sonora.ui.components.DevBadge
import de.yurtemre.sonora.ui.navigation.Changelog
import de.yurtemre.sonora.ui.navigation.Licenses
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import de.yurtemre.sonora.ui.screens.settings.SwitchRow
import de.yurtemre.sonora.ui.screens.settings.settingsHeader
import de.yurtemre.sonora.ui.screens.update.UpdateChannelPicker
import de.yurtemre.sonora.ui.screens.update.UpdateCheckTile
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val URL_SOURCE = "https://github.com/yurtemre7/SonoraKMP"
private const val URL_DEVELOPER = "https://github.com/yurtemre7"
private const val URL_TELEGRAM = "https://t.me/emredev"

/** SET-07 / SET-08: about, updates (P5-22 tiles), changelog, licenses, thank-you card, links, danger zone. */
@Composable
fun SettingsInfoRoute(onBack: () -> Unit, onNavigate: (NavKey) -> Unit = {}) {
    val repo: SettingsRepository = koinInject()
    val settings by repo.settings.collectAsStateWithLifecycle(AppSettings())
    val context = LocalContext.current
    val contextRes = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var wiping by remember { mutableStateOf(false) }
    val wiper = AppWiper(
        context, koinInject(), repo, koinInject(), koinInject(), koinInject(), koinInject(), koinInject(), koinInject(),
    )

    fun open(url: String) {
        if (!UrlOpener.open(context, url)) {
            scope.launch { snackbar.showSnackbar(contextRes.getString(R.string.settings_link_failed)) }
        }
    }

    SettingsScaffold(stringResource(R.string.settings_info_title), onBack, snackbar) {
        item(key = "about") {
            SettingsRow(
                stringResource(R.string.settings_about_title), icon = Icons.Outlined.Info,
                subtitle = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                onClick = { showAbout = true },
                trailing = { DevBadge() },
            )
        }

        settingsHeader("updates") { stringResource(R.string.settings_section_updates) }
        item(key = "update_check") { UpdateCheckTile() }
        item(key = "auto_update") {
            SwitchRow(
                stringResource(R.string.settings_auto_updates), settings.autoCheckUpdates,
                { v -> scope.launch { repo.setAutoCheckUpdates(v) } }, icon = Icons.Rounded.Sync,
                subtitle = stringResource(R.string.settings_auto_updates_subtitle),
            )
        }
        item(key = "channel") { UpdateChannelPicker() }
        item(key = "changelog") {
            SettingsRow(
                stringResource(R.string.settings_changelog), icon = Icons.Rounded.History,
                subtitle = stringResource(R.string.settings_changelog_subtitle), chevron = true,
                onClick = { onNavigate(Changelog) },
            )
        }
        item(key = "licenses") {
            SettingsRow(
                stringResource(R.string.settings_licenses), icon = Icons.Outlined.Description,
                subtitle = stringResource(R.string.settings_licenses_subtitle), chevron = true,
                onClick = { onNavigate(Licenses) },
            )
        }

        item(key = "thanks") { ThankYouCard(settings.userName) }

        settingsHeader("community") { stringResource(R.string.settings_section_community) }
        item(key = "source") {
            SettingsRow(
                stringResource(R.string.settings_source), icon = Icons.Rounded.Code,
                subtitle = stringResource(R.string.settings_source_subtitle), onClick = { open(URL_SOURCE) },
            )
        }
        item(key = "developer") {
            SettingsRow(
                stringResource(R.string.settings_developer), icon = Icons.Rounded.Person,
                subtitle = stringResource(R.string.settings_developer_subtitle), onClick = { open(URL_DEVELOPER) },
            )
        }
        item(key = "telegram") {
            SettingsRow(
                stringResource(R.string.settings_telegram), icon = Icons.AutoMirrored.Rounded.Send,
                subtitle = stringResource(R.string.settings_telegram_subtitle), onClick = { open(URL_TELEGRAM) },
            )
        }

        settingsHeader("danger") { stringResource(R.string.settings_section_danger) }
        item(key = "danger_card") {
            DangerZoneCard(busy = wiping, onHoldComplete = { confirmReset = true })
        }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reset_confirm_title),
            message = stringResource(R.string.settings_reset_confirm_body),
            confirmLabel = stringResource(R.string.settings_reset_action),
            destructive = true,
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                wiping = true
                scope.launch {
                    val ok = runCatching { wiper.wipe() }.isSuccess
                    wiping = false
                    val activity = context.findActivity()
                    if (ok && activity != null) AppWiper.restart(activity)
                    else snackbar.showSnackbar(contextRes.getString(R.string.settings_reset_failed))
                }
            },
        )
    }
}

/** Heartbeat 1400 ms: 1.0 → 1.15 → 1.02 → 1.10 → 1.0, then 40 % rest; static under reduce-motion. */
@Composable
private fun ThankYouCard(userName: String) {
    val cs = MaterialTheme.colorScheme
    val name = userName.trim()
    val title = if (name.isNotEmpty() && !name.equals("user", ignoreCase = true)) {
        stringResource(R.string.settings_thanks_title_name, name)
    } else stringResource(R.string.settings_thanks_title)
    val scale = if (LocalReduceMotion.current) 1f else {
        val beat by rememberInfiniteTransition(label = "heart").animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 1400
                    1f at 0 using LinearEasing
                    1.15f at 210
                    1.02f at 420
                    1.10f at 630
                    1f at 840
                    1f at 1400
                },
            ),
            label = "beat",
        )
        beat
    }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = cs.primaryContainer, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Rounded.Favorite, null, tint = cs.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                )
            }
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Text(stringResource(R.string.settings_thanks_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val contextRes = LocalResources.current
    val icon = remember { runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull() }
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { if (icon != null) Image(icon, null, Modifier.size(80.dp)) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.app_title))
                DevBadge()
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), color = cs.onSurfaceVariant)
                Text(stringResource(R.string.settings_about_description), textAlign = TextAlign.Center)
                HorizontalDivider()
                Text(stringResource(R.string.settings_about_made_with), textAlign = TextAlign.Center)
                Text(stringResource(R.string.settings_about_tech), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
