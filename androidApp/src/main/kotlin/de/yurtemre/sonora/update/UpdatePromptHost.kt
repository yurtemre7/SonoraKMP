package de.yurtemre.sonora.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.ui.screens.update.UpdateSheet

/**
 * Place once in the app shell after the gate is Ready (UPD-05): runs the throttled automatic check on cold start and
 * shows the update sheet at most once per version per process. Also hosts the sheet opened from the Info tile.
 */
@Composable
fun UpdatePromptHost(viewModel: UpdateViewModel = rememberUpdateViewModel()) {
    LaunchedEffect(Unit) { viewModel.autoCheckIfDue() }
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    prompt?.let { update ->
        UpdateSheet(
            update = update,
            installSupported = viewModel.installSupported,
            onDismiss = viewModel::dismissPrompt,
            onDownload = { viewModel.downloadAndInstall(update) },
        )
    }
}
