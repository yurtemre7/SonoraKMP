package de.yurtemre.sonora.ui.screens.jam

import android.content.ActivityNotFoundException
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.yurtemre.sonora.R
import de.yurtemre.sonora.jam.JamController
import de.yurtemre.sonora.jam.JamDiscovery
import de.yurtemre.sonora.jam.JamUiState
import de.yurtemre.sonora.permissions.MediaPermissions
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.ui.components.ConfirmDialog
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings › Jam (and the Jam entries in Now Playing and the weekly mix): one small screen per [JamScreen].
 * [join] opens the join form at once; [host] starts hosting with those permissions once (the caller already played
 * the queue to share). Asks for the local network (required, 37+) and notification (best-effort, 33+) permissions
 * before hosting or joining, like device sync.
 */
@Composable
fun JamRoute(join: Boolean, host: JamSettings?, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val controller: JamController = koinInject()
    val context = LocalContext.current
    val discovery = remember(context) { JamDiscovery(context.applicationContext) }
    val vm = viewModel { JamViewModel(controller, { discovery.discover() }, { SystemClock.elapsedRealtime() }) }
    val state by vm.state.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val joinUi by vm.join.collectAsStateWithLifecycle()
    val confirm by vm.confirm.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    var showStartSheet by rememberSaveable { mutableStateOf(false) }
    var entryHandled by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val res = LocalResources.current

    // What waits for the permission answer, as a saveable key (the activity may be recreated meanwhile).
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    fun run(action: String) {
        when {
            action == ACTION_JOIN -> vm.openJoin()
            action == ACTION_REJOIN -> vm.rejoin()
            action == ACTION_RETRY -> vm.retryHosting()
            action.startsWith(ACTION_HOST) -> vm.startHosting(hostSettingsOf(action))
        }
    }
    fun showLocalNetworkDenied() {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = res.getString(R.string.jam_local_network_denied),
                actionLabel = res.getString(R.string.devicesync_open_settings),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) MediaPermissions.openAppSettings(context)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending
        pending = null
        when {
            action == null -> Unit
            MediaPermissions.hasLocalNetwork(context) -> run(action)
            else -> showLocalNetworkDenied()
        }
    }
    fun withPermissions(action: String) {
        val missing = listOfNotNull(
            MediaPermissions.localNetwork?.takeUnless { MediaPermissions.hasLocalNetwork(context) },
            MediaPermissions.notifications?.takeUnless { MediaPermissions.hasNotifications(context) },
        )
        if (missing.isEmpty()) return run(action)
        pending = action
        try {
            permissionLauncher.launch(missing.toTypedArray())
        } catch (_: ActivityNotFoundException) {
            pending = null
            if (MediaPermissions.hasLocalNetwork(context)) run(action) else showLocalNetworkDenied()
        }
    }

    LaunchedEffect(Unit) {
        if (entryHandled) return@LaunchedEffect
        entryHandled = true
        when {
            host != null -> withPermissions(hostAction(host))
            join && state !is JamUiState.Hosting && state !is JamUiState.Joined -> withPermissions(ACTION_JOIN)
        }
    }

    if (screen == JamScreen.Join) {
        LifecycleStartEffect(vm) {
            vm.startDiscovery()
            onStopOrDispose { vm.stopDiscovery() }
        }
    }

    BackHandler(enabled = screen == JamScreen.Join) {
        when {
            state == JamUiState.Joining -> vm.cancelJoin()
            join -> onBack()
            else -> vm.closeJoin()
        }
    }

    Box(Modifier.fillMaxSize()) {
    when (screen) {
        JamScreen.Start -> JamStartScreen(
            starting = starting,
            onStart = { showStartSheet = true },
            onJoin = { withPermissions(ACTION_JOIN) },
            onBack = onBack,
        )
        JamScreen.Join -> JamJoinScreen(
            joinUi,
            JamJoinActions(vm::select, vm::setManualText, vm::toggleManual, vm::setCode, vm::submit),
            onBack = { if (join) onBack() else vm.closeJoin() },
        )
        JamScreen.Host -> (state as? JamUiState.Hosting)?.let { s ->
            JamHostScreen(
                s,
                onKick = vm::requestKick,
                onCanAdd = vm::setGuestsCanAdd,
                onCanControl = vm::setGuestsCanControl,
                onNewCode = vm::newCode,
                onEnd = vm::requestEnd,
                onBack = onBack,
            )
        }
        JamScreen.Guest -> (state as? JamUiState.Joined)?.let { s ->
            JamGuestScreen(s, onOpenPlayer = onOpenPlayer, onLeave = vm::leave, onBack = onBack)
        }
        JamScreen.Ended -> (state as? JamUiState.Ended)?.let { s ->
            JamEndedScreen(
                s,
                onRejoin = { withPermissions(ACTION_REJOIN) },
                onDone = { vm.dismiss(); onBack() },
                onBack = { vm.dismiss(); onBack() },
            )
        }
        JamScreen.HostFailed -> JamHostFailedScreen(onRetry = { withPermissions(ACTION_RETRY) }, onBack = { vm.dismiss(); onBack() })
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (showStartSheet) {
        JamStartSheet(
            onDismiss = { showStartSheet = false },
            onStart = { settings ->
                showStartSheet = false
                withPermissions(hostAction(settings))
            },
        )
    }

    when (val c = confirm) {
        is JamConfirm.Kick -> ConfirmDialog(
            title = stringResource(R.string.jam_remove_title, c.guest.name),
            message = stringResource(R.string.jam_remove_body),
            confirmLabel = stringResource(R.string.jam_remove_confirm),
            destructive = true,
            onDismiss = vm::dismissConfirm,
            onConfirm = vm::confirm,
        )
        JamConfirm.End -> ConfirmDialog(
            title = stringResource(R.string.jam_end_title),
            message = stringResource(R.string.jam_end_body),
            confirmLabel = stringResource(R.string.jam_end),
            destructive = true,
            onDismiss = vm::dismissConfirm,
            onConfirm = vm::confirm,
        )
        null -> Unit
    }
}

private const val ACTION_JOIN = "join"
private const val ACTION_REJOIN = "rejoin"
private const val ACTION_RETRY = "retry"
private const val ACTION_HOST = "host:"

/** "host:<add>:<control>" with 1/0 flags. */
private fun hostAction(settings: JamSettings): String =
    ACTION_HOST + (if (settings.guestsCanAdd) "1" else "0") + ":" + (if (settings.guestsCanControl) "1" else "0")

private fun hostSettingsOf(action: String): JamSettings {
    val flags = action.removePrefix(ACTION_HOST).split(':')
    return JamSettings(guestsCanAdd = flags.getOrNull(0) == "1", guestsCanControl = flags.getOrNull(1) == "1")
}
