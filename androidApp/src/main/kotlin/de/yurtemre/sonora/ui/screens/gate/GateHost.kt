package de.yurtemre.sonora.ui.screens.gate

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.yurtemre.sonora.R
import de.yurtemre.sonora.permissions.MediaPermissions
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.screens.onboarding.OnboardingScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

/** UI-01 gate states. */
private enum class GateState { Loading, Onboarding, Permission, Error, Ready }

private const val SLOW_MS = 15_000L
private const val TIMEOUT_MS = 30_000L

/**
 * Startup gate (UI-01, LIB-02): Loading → Onboarding | Permission | Error | Ready.
 * [content] is the app shell; the onboarding result snackbar floats above it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GateHost(content: @Composable () -> Unit) {
    val settingsRepository: SettingsRepository = koinInject()
    val libraryRepository: LibraryRepository = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var state by remember { mutableStateOf(GateState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var slow by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        state = GateState.Loading
        slow = false
        val slowJob = launch { delay(SLOW_MS); slow = true }
        val settings = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { settingsRepository.settings.first() }.getOrNull()
        }
        slowJob.cancel()
        state = when {
            settings == null -> GateState.Error
            !settings.onboardingCompleted || settings.scanFolderPath == null -> GateState.Onboarding
            !MediaPermissions.hasAudio(context) -> GateState.Permission
            else -> GateState.Ready
        }
    }

    when (state) {
        GateState.Loading -> GateLoading(slow)
        GateState.Error -> GateError(
            onRetry = {
                scope.launch {
                    runCatching { settingsRepository.edit { it.clear() } }
                    runCatching { libraryRepository.clearAll() }
                    attempt++
                }
            },
        )
        GateState.Permission -> GatePermission(onGranted = { attempt++ })
        GateState.Onboarding -> OnboardingScreen(
            onFinished = { message ->
                state = GateState.Ready
                scope.launch { snackbar.showSnackbar(message) }
            },
        )
        GateState.Ready -> Box(Modifier.fillMaxSize()) {
            content()
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp),
            )
        }
    }
}

@Composable
private fun GateScaffold(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GateLoading(slow: Boolean) = GateScaffold {
    val loading = stringResource(R.string.gate_loading)
    LoadingIndicator(Modifier.semantics { contentDescription = loading })
    if (slow) {
        Text(
            stringResource(R.string.gate_loading_slow),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun GateMessage(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
    Text(
        body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun GateError(onRetry: () -> Unit) = GateScaffold {
    val activity = LocalActivity.current
    GateMessage(stringResource(R.string.gate_error_title), stringResource(R.string.gate_error_body))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.gate_error_clear_retry))
    }
    OutlinedButton(onClick = { activity?.finishAndRemoveTask() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.gate_error_close_app))
    }
}

@Composable
private fun GatePermission(onGranted: () -> Unit) = GateScaffold {
    val activity = LocalActivity.current
    val context = LocalContext.current
    var permanentlyDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[MediaPermissions.audio] == true) {
            onGranted()
        } else {
            permanentlyDenied = activity?.let { MediaPermissions.isPermanentlyDenied(it, MediaPermissions.audio) } ?: false
        }
    }
    // Coming back from system settings with the grant in place.
    LifecycleResumeEffect(Unit) {
        if (MediaPermissions.hasAudio(context)) onGranted()
        onPauseOrDispose { }
    }
    GateMessage(stringResource(R.string.gate_permission_title), stringResource(R.string.gate_permission_body))
    if (permanentlyDenied) {
        Text(
            stringResource(R.string.gate_permission_denied_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { MediaPermissions.openAppSettings(context) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.gate_permission_open_settings)) }
    } else {
        Button(
            onClick = { launcher.launch(MediaPermissions.storageRequest) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.gate_permission_grant)) }
    }
}
