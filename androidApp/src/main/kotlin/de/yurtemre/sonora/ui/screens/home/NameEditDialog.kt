package de.yurtemre.sonora.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.sheets.TextInputDialog
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Quick-edit of the name shown in the Home greeting. Blank saves the default name; also re-enables the greeting. */
@Composable
fun NameEditDialog(current: String, onDismiss: () -> Unit) {
    val settings = koinInject<SettingsRepository>()
    val scope = rememberCoroutineScope()
    TextInputDialog(
        title = stringResource(R.string.settings_name_dialog_title),
        label = stringResource(R.string.settings_name),
        confirmLabel = stringResource(R.string.save),
        onSubmit = { name ->
            scope.launch {
                settings.setUserName(name.trim().ifEmpty { DEFAULT_NAME })
                settings.setUseGreetingTitle(true)
            }
        },
        onDismiss = onDismiss,
        initial = current,
        allowBlank = true,
        supportingText = stringResource(R.string.home_name_dialog_hint),
    )
}
