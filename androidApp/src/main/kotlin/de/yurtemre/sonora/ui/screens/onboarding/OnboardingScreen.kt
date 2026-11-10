package de.yurtemre.sonora.ui.screens.onboarding

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.yurtemre.sonora.R
import de.yurtemre.sonora.permissions.FolderPicker
import de.yurtemre.sonora.permissions.MediaPermissions
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.SyncOutcome
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val PAGE_COUNT = 3

/**
 * Onboarding (UI-02, LIB-01, D-55, D-64): welcome + optional name, permissions, folder.
 * Finishing saves settings, runs the first sync and calls [onFinished] with the snackbar text.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(onFinished: (message: String) -> Unit) {
    val settingsRepository: SettingsRepository = koinInject()
    val libraryRepository: LibraryRepository = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var page by rememberSaveable { mutableStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var folderPath by rememberSaveable { mutableStateOf<String?>(null) }
    var folderUri by rememberSaveable { mutableStateOf<String?>(null) }
    var folderError by rememberSaveable { mutableStateOf(false) }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    var audioGranted by remember { mutableStateOf(MediaPermissions.hasAudio(context)) }
    var notificationsGranted by remember { mutableStateOf(MediaPermissions.hasNotifications(context)) }
    var finishing by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        audioGranted = MediaPermissions.hasAudio(context)
        notificationsGranted = MediaPermissions.hasNotifications(context)
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notificationsGranted = ok
        notificationsAsked = true
        if (audioGranted) page = 2
    }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        audioGranted = result[MediaPermissions.audio] == true || MediaPermissions.hasAudio(context)
        val notif = MediaPermissions.notifications
        if (notif != null && !notificationsGranted) notificationLauncher.launch(notif)
        else if (audioGranted) page = 2
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val picked = FolderPicker.persist(context, uri)
            folderError = picked == null
            if (picked != null) {
                folderPath = picked.path
                folderUri = picked.uri
            }
        }
    }

    // Notifications are best-effort: once asked (or granted) they no longer block.
    val permissionsDone = audioGranted && (notificationsGranted || notificationsAsked)
    val fabEnabled = when (page) {
        0 -> true
        1 -> permissionsDone
        else -> folderPath != null
    }

    val fabLabel = stringResource(if (page == PAGE_COUNT - 1) R.string.onboarding_get_started else R.string.onboarding_next)
    val fabDisabled = stringResource(R.string.onboarding_fab_disabled)
    val fabDescription = if (fabEnabled) fabLabel else "$fabLabel, $fabDisabled"

    BackHandler(enabled = page > 0 && !finishing) { page-- }

    fun finish() {
        val path = folderPath ?: return
        finishing = true
        scope.launch {
            val trimmed = name.trim()
            // D-64: the greeting is turned on only when a name was entered; no default "User".
            if (trimmed.isNotEmpty()) {
                settingsRepository.setUserName(trimmed)
                settingsRepository.setUseGreetingTitle(true)
            }
            settingsRepository.setScanFolder(path, folderUri)
            settingsRepository.setOnboardingCompleted(true)
            val outcome = libraryRepository.sync()
            onFinished(finishMessage(context, outcome))
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (finishing) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LoadingIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.onboarding_scanning), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.onboarding_scanning_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Surface
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    PageDots(page)
                }
                AnimatedContent(
                    targetState = page,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = Modifier.weight(1f),
                    label = "onboardingPage",
                ) { p ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(top = 32.dp, bottom = 96.dp)
                            .widthIn(max = 560.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (p) {
                            0 -> WelcomePage(name, onName = { name = it }, onDone = { page = 1 })
                            1 -> PermissionsPage(
                                audioGranted = audioGranted,
                                notificationsGranted = notificationsGranted,
                                onRequest = {
                                    if (!audioGranted) storageLauncher.launch(MediaPermissions.storageRequest)
                                    else MediaPermissions.notifications?.let(notificationLauncher::launch)
                                },
                                onOpenSettings = { MediaPermissions.openAppSettings(context) },
                                showSettings = notificationsAsked && !audioGranted,
                            )
                            else -> FolderPage(folderPath, folderError, onPick = { treeLauncher.launch(null) })
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = {
                    if (!fabEnabled) return@ExtendedFloatingActionButton
                    if (page < PAGE_COUNT - 1) page++ else finish()
                },
                expanded = page == PAGE_COUNT - 1,
                icon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null) },
                text = { Text(stringResource(R.string.onboarding_get_started)) },
                containerColor = if (fabEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (fabEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).semantics {
                    contentDescription = fabDescription
                },
            )
        }
    }
}

private fun finishMessage(context: Context, outcome: SyncOutcome): String = when (outcome) {
    is SyncOutcome.Success -> context.resources.getQuantityString(
        R.plurals.onboarding_folder_imported, outcome.songCount, outcome.songCount,
    )
    else -> context.getString(R.string.onboarding_folder_configured)
}

@Composable
private fun PageDots(page: Int) {
    val label = stringResource(R.string.onboarding_step, page + 1, PAGE_COUNT)
    Row(
        Modifier.semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PAGE_COUNT) { i ->
            val width by animateDpAsState(if (i == page) 24.dp else 8.dp, label = "dot")
            Box(
                Modifier.size(width = width, height = 8.dp).background(
                    if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
            )
        }
    }
}

@Composable
private fun PageTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WelcomePage(name: String, onName: (String) -> Unit, onDone: () -> Unit) {
    PageTitle(stringResource(R.string.onboarding_welcome_title), stringResource(R.string.onboarding_welcome_body))
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text(stringResource(R.string.onboarding_name_label)) },
        supportingText = { Text(stringResource(R.string.onboarding_name_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PermissionsPage(
    audioGranted: Boolean,
    notificationsGranted: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    showSettings: Boolean,
) {
    PageTitle(stringResource(R.string.onboarding_permissions_title), stringResource(R.string.onboarding_permissions_body))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            PermissionRow(
                Icons.Rounded.LibraryMusic,
                stringResource(R.string.onboarding_permission_audio),
                stringResource(R.string.onboarding_permission_audio_body),
                audioGranted,
            )
            PermissionRow(
                Icons.Rounded.Notifications,
                stringResource(R.string.onboarding_permission_notifications),
                stringResource(R.string.onboarding_permission_notifications_body),
                notificationsGranted,
            )
        }
    }
    if (!(audioGranted && notificationsGranted)) {
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.onboarding_permissions_allow))
        }
    }
    if (showSettings) {
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.gate_permission_open_settings))
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, body: String, granted: Boolean) {
    val status = stringResource(if (granted) R.string.onboarding_status_granted else R.string.onboarding_status_not_granted)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $status" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun FolderPage(folderPath: String?, error: Boolean, onPick: () -> Unit) {
    PageTitle(stringResource(R.string.onboarding_folder_title), stringResource(R.string.onboarding_folder_body))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_folder_selected), style = MaterialTheme.typography.labelMedium)
                Text(
                    folderPath ?: stringResource(R.string.onboarding_folder_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (folderPath == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    if (error) {
        Text(
            stringResource(R.string.onboarding_folder_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    val pick = stringResource(if (folderPath == null) R.string.onboarding_folder_choose else R.string.onboarding_folder_change)
    if (folderPath == null) {
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(pick) }
    } else {
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(pick) }
    }
}
