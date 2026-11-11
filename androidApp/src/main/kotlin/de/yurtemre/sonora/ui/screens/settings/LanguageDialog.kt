package de.yurtemre.sonora.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.AppLocale

/** Language names are shown in their own language (L10N-03); "System default" follows the UI language. */
@Composable
fun languageLabel(locale: AppLocale): String = when (locale) {
    AppLocale.System -> stringResource(R.string.language_system)
    AppLocale.English -> stringResource(R.string.settings_language_english)
    AppLocale.Japanese -> stringResource(R.string.settings_language_japanese)
}

@Composable
fun LanguageDialog(current: AppLocale, onSelect: (AppLocale) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_dialog_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AppLocale.entries.forEach { locale ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = locale == current, role = Role.RadioButton) { onSelect(locale) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = locale == current, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(languageLabel(locale), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
