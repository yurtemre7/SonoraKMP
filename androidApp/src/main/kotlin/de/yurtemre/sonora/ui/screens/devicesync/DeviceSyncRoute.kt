package de.yurtemre.sonora.ui.screens.devicesync

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.IntentSender
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.devicesync.DeviceSyncController
import de.yurtemre.sonora.devicesync.DeviceSyncState
import de.yurtemre.sonora.locale.AppLocales
import de.yurtemre.sonora.permissions.FolderPicker
import de.yurtemre.sonora.permissions.MediaPermissions
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.components.ConfirmDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class SyncRole { Send, Receive }

/**
 * Settings › Device sync (DSYNC-01..13): follows [DeviceSyncController.state] and shows one small screen per state.
 * Owns the platform flows: local network (37+, required) and notification (33+, best-effort) permissions, the folder picker (write access), the
 * MediaStore delete confirmation and applying a synced app language.
 */
@Composable
fun DeviceSyncRoute(onBack: () -> Unit) {
    val controller: DeviceSyncController = koinInject()
    val settingsRepo: SettingsRepository = koinInject()
    val state by controller.state.collectAsStateWithLifecycle()
    val deleteRequest by controller.pendingDeleteRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingRole by rememberSaveable { mutableStateOf<SyncRole?>(null) }
    var folderError by rememberSaveable { mutableStateOf(false) }
    var autoPicked by rememberSaveable { mutableStateOf(false) }
    var localeApplied by rememberSaveable { mutableStateOf(false) }
    var launchedDelete by rememberSaveable { mutableStateOf<IntentSender?>(null) }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current

    fun showMessage(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    fun start(role: SyncRole) = when (role) {
        SyncRole.Send -> controller.startSending()
        SyncRole.Receive -> controller.startReceiving()
    }

    // Android 17+: no discovery or connection to the other phone without the local network grant (required).
    fun showLocalNetworkDenied() {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = res.getString(R.string.devicesync_local_network_denied),
                actionLabel = res.getString(R.string.devicesync_open_settings),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) MediaPermissions.openAppSettings(context)
        }
    }

    // The foreground service notification needs POST_NOTIFICATIONS on 33+ (best-effort); the local network grant
    // (37+) is required. Both are asked together before sending or receiving.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val role = pendingRole
        pendingRole = null
        when {
            role == null -> Unit
            MediaPermissions.hasLocalNetwork(context) -> start(role)
            else -> showLocalNetworkDenied()
        }
    }

    fun startWithNotifications(role: SyncRole) {
        val missing = listOfNotNull(
            MediaPermissions.localNetwork?.takeUnless { MediaPermissions.hasLocalNetwork(context) },
            MediaPermissions.notifications?.takeUnless { MediaPermissions.hasNotifications(context) },
        )
        if (missing.isEmpty()) return start(role)
        pendingRole = role
        try {
            permissionLauncher.launch(missing.toTypedArray())
        } catch (_: ActivityNotFoundException) {
            pendingRole = null
            if (MediaPermissions.hasLocalNetwork(context)) start(role) else showLocalNetworkDenied()
        }
    }

    // The folder picker stops this activity: that must not count as leaving the screen (see onScreenHidden below).
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pickerOpen = false
        if (uri == null) return@rememberLauncherForActivityResult
        if (FolderPicker.persistWritable(context, uri) == null) {
            folderError = true
        } else {
            folderError = false
            controller.setDestination(uri)
        }
    }

    fun pickFolder() {
        scope.launch {
            val initial = controller.pickerInitialUri()
            try {
                pickerOpen = true
                treeLauncher.launch(initial)
            } catch (_: ActivityNotFoundException) {
                pickerOpen = false
                folderError = true
            }
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        launchedDelete = null
        controller.onDeleteResult(result.resultCode == Activity.RESULT_OK)
    }

    // DSYNC-12: system confirmation for deleting replaced MediaStore files; launched once per request.
    LaunchedEffect(deleteRequest) {
        val request = deleteRequest ?: return@LaunchedEffect
        if (request == launchedDelete) return@LaunchedEffect
        launchedDelete = request
        try {
            deleteLauncher.launch(IntentSenderRequest.Builder(request).build())
        } catch (_: ActivityNotFoundException) {
            launchedDelete = null
            controller.onDeleteResult(false)
        }
    }

    val planning = state as? DeviceSyncState.Planning
    LaunchedEffect(planning?.needsDestination) {
        if (planning?.needsDestination == true && !autoPicked) {
            autoPicked = true
            pickFolder()
        }
    }

    val summary = (state as? DeviceSyncState.Finished)?.summary
    LaunchedEffect(summary) {
        if (summary?.localeChanged == true && !localeApplied) {
            localeApplied = true
            AppLocales.apply(settingsRepo.settings.first().appLocale)
        }
    }

    LaunchedEffect(state is DeviceSyncState.Idle) {
        if (state is DeviceSyncState.Idle) {
            autoPicked = false
            localeApplied = false
            folderError = false
        }
    }

    // The cancel dialog belongs to one transfer: drop a pending request once it is over (failed, applying, …).
    val transferring = state is DeviceSyncState.Transferring
    LaunchedEffect(transferring) {
        if (!transferring) confirmCancel = false
    }

    fun finish() {
        controller.cancel()
        onBack()
    }

    fun handleBack() {
        when (state) {
            DeviceSyncState.Idle -> onBack()
            is DeviceSyncState.Transferring -> confirmCancel = true
            // Applying (library update, replace, data) is short and must not be cut off: back waits for it.
            is DeviceSyncState.Applying -> showMessage(res.getString(R.string.devicesync_applying_wait))
            is DeviceSyncState.Finished -> finish()
            // Preparing and sending (stop the sender), discovery, connecting, planning, failed: back to the start screen.
            else -> controller.cancel()
        }
    }

    BackHandler(enabled = state !is DeviceSyncState.Idle) { handleBack() }

    // I4: leaving the screen (app in the background, screen off, navigated away) stops what the foreground service
    // does not cover: preparing, discovery (multicast lock), connecting and planning (an open session). A
    // configuration change or the folder picker is not leaving.
    val activity = LocalActivity.current
    val currentPickerOpen by rememberUpdatedState(pickerOpen)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true && !currentPickerOpen) controller.onScreenHidden()
    }
    DisposableEffect(controller) {
        onDispose { if (activity?.isChangingConfigurations != true) controller.onScreenHidden() }
    }

    // The screen-off reset above would throw away a long folder walk, pairing or first plan on a big library, so the
    // screen stays on while one of those runs (the user is waiting on it anyway).
    val view = LocalView.current
    val busy = state is DeviceSyncState.Preparing || state is DeviceSyncState.Connecting ||
        (state as? DeviceSyncState.Planning)?.computing == true
    DisposableEffect(view, busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    when (val s = state) {
        DeviceSyncState.Idle -> DeviceSyncStartScreen(
            onSend = { startWithNotifications(SyncRole.Send) },
            onReceive = { startWithNotifications(SyncRole.Receive) },
            onBack = ::handleBack,
        )
        DeviceSyncState.Preparing -> DeviceSyncPreparingScreen(onCancel = controller::cancel, onBack = ::handleBack)
        is DeviceSyncState.Sending -> DeviceSyncSenderScreen(s, onStop = controller::cancel, onBack = ::handleBack)
        // One call site for both states, so the typed code stays visible while connecting.
        is DeviceSyncState.Discovering, is DeviceSyncState.Connecting -> DeviceSyncConnectScreen(
            discovering = s as? DeviceSyncState.Discovering,
            connecting = (s as? DeviceSyncState.Connecting)?.target,
            onConnect = controller::connect,
            onSearchAgain = controller::searchAgain,
            onBack = ::handleBack,
        )
        is DeviceSyncState.Planning -> DeviceSyncSelectionScreen(
            s,
            folderError = folderError,
            snackbar = snackbar,
            onPickFolder = ::pickFolder,
            onStart = { ids ->
                if (!controller.startTransfer(ids)) showMessage(res.getString(R.string.devicesync_start_refused))
            },
            onBack = ::handleBack,
        )
        is DeviceSyncState.Transferring, is DeviceSyncState.Applying ->
            DeviceSyncProgressScreen(s, snackbar, onCancel = { confirmCancel = true }, onBack = ::handleBack)
        is DeviceSyncState.Finished -> {
            val result = s.summary
            if (result == null) DeviceSyncSentScreen(onDone = ::finish, onBack = ::handleBack)
            else DeviceSyncResultScreen(result, onDone = ::finish, onBack = ::handleBack)
        }
        is DeviceSyncState.Failed -> DeviceSyncFailedScreen(s, onRetry = controller::retry, onBack = ::handleBack)
    }

    if (confirmCancel && transferring) {
        ConfirmDialog(
            title = stringResource(R.string.devicesync_cancel_title),
            message = stringResource(R.string.devicesync_cancel_body),
            confirmLabel = stringResource(R.string.devicesync_cancel_confirm),
            destructive = true,
            dismissLabel = stringResource(R.string.devicesync_keep_going),
            onDismiss = { confirmCancel = false },
            onConfirm = controller::cancel,
        )
    }
}
