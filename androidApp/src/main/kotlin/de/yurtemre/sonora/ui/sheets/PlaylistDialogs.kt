package de.yurtemre.sonora.ui.sheets

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.SonoraTheme

/**
 * Single text-field dialog shared by the playlist dialogs (UI-30). [onSubmit] receives the trimmed text;
 * when [allowBlank] is false a blank value disables the confirm button.
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    allowBlank: Boolean = false,
    singleLine: Boolean = true,
    supportingText: String? = null,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val canSubmit = allowBlank || value.text.isNotBlank()
    val submit = { if (canSubmit) { onSubmit(value.text.trim()); onDismiss() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                supportingText = supportingText?.let { { Text(it) } },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 2,
                maxLines = if (singleLine) 1 else 5,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus).testTag("text_input_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = canSubmit) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sheets_dialog_cancel)) }
        },
    )
}


/** Create playlist: [onCreate] receives the trimmed, non-blank name. */
@Composable
fun CreatePlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) = TextInputDialog(
    title = stringResource(R.string.sheets_dialog_create_title),
    label = stringResource(R.string.sheets_dialog_name_label),
    confirmLabel = stringResource(R.string.sheets_dialog_create),
    onSubmit = onCreate,
    onDismiss = onDismiss,
)

/** Rename (prefilled). A blank or unchanged name is a no-op: [onRename] is not called. */
@Composable
fun RenamePlaylistDialog(currentName: String, onRename: (String) -> Unit, onDismiss: () -> Unit) = TextInputDialog(
    title = stringResource(R.string.sheets_dialog_rename_title),
    label = stringResource(R.string.sheets_dialog_name_label),
    confirmLabel = stringResource(R.string.sheets_dialog_save),
    initial = currentName,
    onSubmit = { if (it.isNotBlank() && it != currentName) onRename(it) },
    onDismiss = onDismiss,
)

/** Edit description: blank → null (clears it). */
@Composable
fun EditDescriptionDialog(currentDescription: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) = TextInputDialog(
    title = stringResource(R.string.sheets_dialog_description_title),
    label = stringResource(R.string.sheets_dialog_description_label),
    confirmLabel = stringResource(R.string.sheets_dialog_save),
    initial = currentDescription.orEmpty(),
    allowBlank = true,
    singleLine = false,
    supportingText = stringResource(R.string.sheets_dialog_description_hint),
    onSubmit = { onSave(it.ifBlank { null }) },
    onDismiss = onDismiss,
)

/**
 * Save queue as playlist. [onSave] receives the name; the caller calls `PlaylistRepository.createFromQueue`
 * and shows [R.string.sheets_dialog_saved_queue].
 */
@Composable
fun SaveQueueAsPlaylistDialog(onSave: (String) -> Unit, onDismiss: () -> Unit, defaultName: String = "") = TextInputDialog(
    title = stringResource(R.string.sheets_dialog_save_queue_title),
    label = stringResource(R.string.sheets_dialog_name_label),
    confirmLabel = stringResource(R.string.sheets_dialog_save),
    initial = defaultName,
    onSubmit = onSave,
    onDismiss = onDismiss,
)

@Preview
@Composable
private fun RenameDialogPreview() = SonoraTheme { RenamePlaylistDialog("Late night", {}, {}) }
