package de.yurtemre.sonora.ui.sheets

import android.content.ClipData
import de.yurtemre.sonora.ui.components.displayTitle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.platform.FolderOpener
import de.yurtemre.sonora.platform.ShareHelper
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.ui.components.AlbumArt
import de.yurtemre.sonora.ui.components.ArtSize
import de.yurtemre.sonora.ui.format.DateFormats
import de.yurtemre.sonora.ui.format.DurationFormat
import de.yurtemre.sonora.ui.format.SizeFormat
import de.yurtemre.sonora.ui.format.currentLocale
import de.yurtemre.sonora.ui.theme.SleeveShape
import de.yurtemre.sonora.ui.theme.tabular
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

/** A label/value row; [copyable] rows copy their value on tap. */
private data class InfoRow(val label: String, val value: String, val copyable: Boolean = true)

/**
 * Song info sheet (UI-28). Metadata, file and audio groups; the audio group is shown only when it has
 * at least one row (D-17). Tapping a value copies it; the path is selectable and has its own actions.
 * Snackbars show inside the sheet so they are visible above the scrim.
 */
@Composable
fun SongInfoSheet(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val locale = currentLocale()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val copiedMsg = stringResource(R.string.sheets_info_copied)
    val folderFailed = stringResource(R.string.sheets_info_open_folder_failed)
    val shareFailed = stringResource(R.string.sheets_info_share_failed)
    fun snack(msg: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(msg)
    }
    fun copy(value: String) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(value, value))) }
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        snack(copiedMsg)
    }

    val metadata = buildList {
        add(InfoRow(stringResource(R.string.sheets_info_title_label), song.title))
        add(InfoRow(stringResource(R.string.sheets_info_artist_label), song.artist))
        add(InfoRow(stringResource(R.string.sheets_info_album_label), song.album))
        song.albumArtist?.takeIf { it.isNotBlank() }?.let { add(InfoRow(stringResource(R.string.sheets_info_album_artist_label), it)) }
        song.trackNumber?.takeIf { it > 0 }?.let { add(InfoRow(stringResource(R.string.sheets_info_track_label), it.toString())) }
        song.discNumber?.takeIf { it > 0 }?.let { add(InfoRow(stringResource(R.string.sheets_info_disc_label), it.toString())) }
        song.genre?.takeIf { it.isNotBlank() }?.let { add(InfoRow(stringResource(R.string.sheets_info_genre_label), it)) }
        song.year?.takeIf { it > 0 }?.let { add(InfoRow(stringResource(R.string.sheets_info_year_label), it.toString())) }
        add(InfoRow(stringResource(R.string.sheets_info_duration_label), DurationFormat.clockMs(song.durationMs)))
    }
    val file = buildList {
        song.fileSize?.takeIf { it > 0 }?.let { add(InfoRow(stringResource(R.string.sheets_info_size_label), SizeFormat.fileSize(context, it))) }
        song.lastModifiedMs?.let {
            add(InfoRow(stringResource(R.string.sheets_info_modified_label), DateFormats.dateTime(Instant.ofEpochMilli(it), locale)))
        }
        song.dateAddedMs?.takeIf { added -> song.lastModifiedMs == null || kotlin.math.abs(added - song.lastModifiedMs!!) > 1000 }?.let {
            add(InfoRow(stringResource(R.string.sheets_info_added_label), DateFormats.dateTime(Instant.ofEpochMilli(it), locale)))
        }
        val fmt = song.format?.takeIf { it.isNotBlank() } ?: song.filePath.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        fmt?.let { add(InfoRow(stringResource(R.string.sheets_info_format_label), it.uppercase(Locale.ROOT))) }
    }
    val rg = song.replayGain
    val audio = buildList {
        song.bitrate?.takeIf { it > 0 }?.let {
            val kbps = if (it >= 10_000) it / 1000 else it
            add(InfoRow(stringResource(R.string.sheets_info_bitrate_label), stringResource(R.string.sheets_info_bitrate_value, kbps)))
        }
        song.sampleRate?.takeIf { it > 0 }?.let {
            val khz = String.format(locale, "%.1f", it / 1000.0).removeSuffix(".0").removeSuffix(",0")
            add(InfoRow(stringResource(R.string.sheets_info_sample_rate_label), stringResource(R.string.sheets_info_sample_rate_value, khz)))
        }
        rg?.trackGain?.let { add(InfoRow(stringResource(R.string.sheets_info_rg_track_label), stringResource(R.string.sheets_info_gain_value, signed(it)))) }
        rg?.albumGain?.let { add(InfoRow(stringResource(R.string.sheets_info_rg_album_label), stringResource(R.string.sheets_info_gain_value, signed(it)))) }
        rg?.trackPeak?.let { add(InfoRow(stringResource(R.string.sheets_info_rg_track_peak_label), String.format(Locale.ROOT, "%.4f", it))) }
        rg?.albumPeak?.let { add(InfoRow(stringResource(R.string.sheets_info_rg_album_peak_label), String.format(Locale.ROOT, "%.4f", it))) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Box {
            LazyColumn(Modifier.fillMaxWidth().testTag("song_info_sheet")) {
                item(key = "header") { Header(song) }
                item(key = "g_meta") { GroupTitle(stringResource(R.string.sheets_info_group_metadata)) }
                metadata.forEach { row -> item(key = "m_${row.label}") { ValueRow(row) { copy(row.value) } } }
                item(key = "g_file") { GroupTitle(stringResource(R.string.sheets_info_group_file)) }
                item(key = "path") {
                    PathBlock(
                        path = song.filePath,
                        onOpenFolder = {
                            if (FolderOpener.open(context, song.filePath) == FolderOpener.Result.FAILED) snack(folderFailed)
                        },
                        onCopy = { copy(song.filePath) },
                        onShare = {
                            if (!ShareHelper.share(context, listOf(ShareHelper.Item(song.filePath, song.id.takeIf { it > 0 })))) {
                                snack(shareFailed)
                            }
                        },
                    )
                }
                file.forEach { row -> item(key = "f_${row.label}") { ValueRow(row) { copy(row.value) } } }
                if (audio.isNotEmpty()) {
                    item(key = "g_audio") { GroupTitle(stringResource(R.string.sheets_info_group_audio)) }
                    audio.forEach { row -> item(key = "a_${row.label}") { ValueRow(row) { copy(row.value) } } }
                }
                item(key = "bottom") { Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

private fun signed(v: Double): String = (if (v >= 0) "+" else "") + String.format(Locale.ROOT, "%.2f", v)

@Composable
private fun Header(song: Song) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AlbumArt(song.artworkPath, song.album, Modifier.size(72.dp), size = ArtSize.Small, shape = SleeveShape, artist = song.artist)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.sheets_info_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(song.displayTitle(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun ValueRow(row: InfoRow, onCopy: () -> Unit) {
    val copyLabel = stringResource(R.string.sheets_info_copy_action, row.label)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = row.copyable, onClickLabel = copyLabel, onClick = onCopy)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            row.value,
            style = MaterialTheme.typography.bodyLarge.tabular(),
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Composable
private fun PathBlock(path: String, onOpenFolder: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    val copyLabel = stringResource(R.string.sheets_info_copy_path)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.sheets_info_path_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer(
            Modifier.semantics { customActions = listOf(CustomAccessibilityAction(copyLabel) { onCopy(); true }) },
        ) {
            Text(path, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp).testTag("song_info_path"))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            PathAction(Icons.Rounded.FolderOpen, R.string.sheets_info_open_folder, onOpenFolder, Modifier.weight(1f))
            PathAction(Icons.Rounded.ContentCopy, R.string.sheets_info_copy_path, onCopy, Modifier.weight(1f))
            PathAction(Icons.Rounded.Share, R.string.sheets_info_share, onShare, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PathAction(icon: ImageVector, label: Int, onClick: () -> Unit, modifier: Modifier) {
    val text = stringResource(label)
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.SmallContentPadding,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
