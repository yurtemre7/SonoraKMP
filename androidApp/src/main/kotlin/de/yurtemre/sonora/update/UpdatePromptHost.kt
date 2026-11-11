package de.yurtemre.sonora.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.ui.screens.update.UpdateSheet

/**
 * Place once in the app shell after the gate is Ready (UPD-05): runs the throttled automatic check on cold start and
 * shows the update sheet at most once per version per process. Also hosts the sheet opened from the Info tile and the
 * download/install progress; re-checks the unknown-sources permission on resume.
 */
@Composable
fun UpdatePromptHost(viewModel: UpdateViewModel = rememberUpdateViewModel()) {
    LaunchedEffect(Unit) { viewModel.autoCheckIfDue() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    prompt?.let { update ->
        UpdateSheet(
            update = update,
            state = state,
            installSupported = viewModel.installSupported,
            onDismiss = viewModel::dismissPrompt,
            onDownload = { viewModel.downloadAndInstall(update) },
            onCancel = viewModel::cancelDownload,
            onRetry = viewModel::retry,
            onOpenPermissionSettings = viewModel::openPermissionSettings,
        )
    }
}
