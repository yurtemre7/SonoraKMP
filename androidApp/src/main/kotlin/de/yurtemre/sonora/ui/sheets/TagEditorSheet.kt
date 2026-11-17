package de.yurtemre.sonora.ui.sheets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.TagFileAccess
import de.yurtemre.sonora.shared.library.TagEdit
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Tag editor: title, artist, album, album artist, genre, year, track, disc and lyrics. Saving writes into the
 * audio file (after the system's "allow modifying this file" prompt) and then updates the library row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagEditorSheet(song: Song, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val library: LibraryRepository = koinInject()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var edit by remember { mutableStateOf(TagEdit.from(song)) }
    // The form as read from the file itself: only fields that differ from it get written.
    var baseline by remember { mutableStateOf<TagEdit?>(null) }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var showErrors by remember { mutableStateOf(false) }

    LaunchedEffect(song.id) {
        val u = TagFileAccess.uriFor(context, song.filePath)
        if (u == null) {
            message = R.string.tag_edit_not_indexed
        } else {
            val fromFile = TagFileAccess.readEditable(context, u, song.filePath, song.title)
            if (fromFile == null) {
                message = R.string.tag_edit_unreadable // unknown current tags: editing could erase them, so don't
            } else {
                uri = u
                baseline = fromFile
                edit = fromFile
            }
        }
        loading = false
    }

    fun doWrite() {
        val u = uri ?: return
        val base = baseline ?: return
        saving = true
        val submitted = edit
        scope.launch {
            // File write, scan wait and Room update commit together: a recreated activity must not cancel
            // the save between the file and the library.
            val result = withContext(NonCancellable) {
                val failure = TagFileAccess.write(context, u, song.filePath, submitted.normalized(), base)
                if (failure != null) return@withContext SaveResult.FileFailed
                // The file now holds `submitted`: retries must diff against it, not the stale load.
                baseline = submitted.normalized()
                try { library.updateTags(song.id, submitted); SaveResult.Saved } catch (_: Throwable) { SaveResult.LibraryFailed }
            }
            if (result == SaveResult.Saved) {
                onSaved()
                onDismiss()
            } else {
                saving = false
                message = R.string.tag_edit_save_failed
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) doWrite() else { saving = false; message = R.string.tag_edit_permission_denied }
    }

    fun save() {
        showErrors = true
        if (edit.error() != null) return
        val u = uri ?: return
        message = null
        saving = true
        val sender = try { TagFileAccess.writeRequest(context, u) } catch (_: Throwable) { null }
        if (sender != null) permissionLauncher.launch(IntentSenderRequest.Builder(sender).build()) else doWrite()
    }

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                .testTag("tag_editor_sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.tag_edit_title), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            val enabled = !loading && !saving && uri != null
            val err = if (showErrors) edit.error() else null
            Field(R.string.sheets_info_title_label, edit.title, enabled, err == TagEdit.Error.EmptyTitle, R.string.tag_edit_error_title) { edit = edit.copy(title = it) }
            Field(R.string.sheets_info_artist_label, edit.artist, enabled) { edit = edit.copy(artist = it) }
            Field(R.string.sheets_info_album_label, edit.album, enabled) { edit = edit.copy(album = it) }
            Field(R.string.sheets_info_album_artist_label, edit.albumArtist, enabled) { edit = edit.copy(albumArtist = it) }
            Field(R.string.sheets_info_genre_label, edit.genre, enabled) { edit = edit.copy(genre = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(R.string.sheets_info_year_label, edit.year, enabled, err == TagEdit.Error.BadYear, R.string.tag_edit_error_number, number = true, modifier = Modifier.weight(1f)) { edit = edit.copy(year = it) }
                Field(R.string.sheets_info_track_label, edit.track, enabled, err == TagEdit.Error.BadTrack, R.string.tag_edit_error_number, number = true, modifier = Modifier.weight(1f)) { edit = edit.copy(track = it) }
                Field(R.string.sheets_info_disc_label, edit.disc, enabled, err == TagEdit.Error.BadDisc, R.string.tag_edit_error_number, number = true, modifier = Modifier.weight(1f)) { edit = edit.copy(disc = it) }
            }
            Field(R.string.tag_edit_lyrics, edit.lyrics, enabled, singleLine = false) { edit = edit.copy(lyrics = it) }
            message?.let { Text(stringResource(it), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.tag_edit_cancel)) }
                Button(onClick = ::save, enabled = enabled, modifier = Modifier.testTag("tag_editor_save")) {
                    Text(stringResource(if (saving) R.string.tag_edit_saving else R.string.tag_edit_save))
                }
            }
        }
    }
}

private enum class SaveResult { Saved, FileFailed, LibraryFailed }

@Composable
private fun Field(
    label: Int,
    value: String,
    enabled: Boolean,
    isError: Boolean = false,
    errorText: Int? = null,
    number: Boolean = false,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        enabled = enabled,
        isError = isError,
        supportingText = if (isError && errorText != null) ({ Text(stringResource(errorText)) }) else null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 4,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier,
    )
}
