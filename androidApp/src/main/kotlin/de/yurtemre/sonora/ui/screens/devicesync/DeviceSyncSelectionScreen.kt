package de.yurtemre.sonora.ui.screens.devicesync

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.devicesync.DeviceSyncState
import de.yurtemre.sonora.shared.devicesync.FileKind
import de.yurtemre.sonora.shared.devicesync.PlanItem
import de.yurtemre.sonora.shared.devicesync.Status
import de.yurtemre.sonora.shared.devicesync.SyncPaths
import de.yurtemre.sonora.shared.devicesync.TransferPlan
import de.yurtemre.sonora.shared.platform.TreeUriPaths
import de.yurtemre.sonora.ui.components.SectionHeader
import de.yurtemre.sonora.ui.format.FolderDisplay
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold

/** Deselected file ids survive configuration changes (thousands of ids fit an IntArray). */
private val IdSetSaver = Saver<Set<Int>, IntArray>(save = { it.toIntArray() }, restore = { it.toSet() })

/**
 * DSYNC-09/10: destination folder, summary chips, Audio (format chips) / Lyrics / Covers grouped by folder with
 * checkboxes, and the bottom bar with the space line and Start ("Sync data only" when nothing is selected).
 */
@Composable
internal fun DeviceSyncSelectionScreen(
    planning: DeviceSyncState.Planning,
    folderError: Boolean,
    snackbar: SnackbarHostState,
    onPickFolder: () -> Unit,
    onStart: (Set<Int>, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val plan = planning.plan
    // Kept per sender: a re-plan (same or another folder) keeps what the user deselected; the rest follows the plan.
    var deselected by rememberSaveable(planning.senderName, stateSaver = IdSetSaver) { mutableStateOf(emptySet<Int>()) }
    // Off by default and per sync: deleting is never a side effect of just pressing Start.
    var removeExtras by rememberSaveable(planning.senderName) { mutableStateOf(false) }
    val selected = remember(plan, deselected) { plan?.let { selectionOf(it, deselected) }.orEmpty() }
    val sections = remember(plan) { plan?.let(::sections).orEmpty() }
    SettingsScaffold(
        stringResource(R.string.settings_devicesync_title),
        onBack,
        snackbar = snackbar,
        bottomBar = {
            if (plan != null && !planning.computing) {
                StartBar(plan, selected, planning.freeBytes) { onStart(selected, removeExtras && plan.extras.isNotEmpty()) }
            }
        },
    ) {
        item(key = "from") { SettingsNote(stringResource(R.string.devicesync_from, planning.senderName)) }
        item(key = "destination") { DestinationCard(planning.destination, folderError, onPickFolder) }
        if (plan == null || planning.computing) {
            if (!planning.needsDestination) {
                item(key = "planning") { StatusProgress(stringResource(R.string.devicesync_planning), null) }
            }
            return@SettingsScaffold
        }
        item(key = "counts") { SummaryChips(planCounts(plan)) }
        if (isAlreadySynced(plan)) {
            item(key = "all_present") {
                DeviceSyncCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        stringResource(R.string.devicesync_all_present),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        } else {
            item(key = "select_all") {
                ActionsRow {
                    TextButton(onClick = { deselected = emptySet() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.devicesync_select_all))
                    }
                    TextButton(onClick = { deselected = selectNone(plan, deselected) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.devicesync_select_none))
                    }
                }
            }
        }
        if (plan.extras.isNotEmpty()) {
            item(key = "extras", contentType = "extras") {
                ExtrasCard(plan, removeExtras) { removeExtras = it }
            }
        }
        sections.forEach { section ->
            sectionItems(section, plan, selected) { deselected = deselectedAfter(plan, deselected, it) }
        }
    }
}

@Composable
private fun ExtrasCard(plan: TransferPlan, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val extras = plan.extras
    DeviceSyncCard(color = if (enabled) cs.errorContainer else cs.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.devicesync_extras_title, extras.size, extras.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) cs.onErrorContainer else cs.onSurface,
                )
                Text(
                    stringResource(R.string.devicesync_extras_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) cs.onErrorContainer else cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = null)
        }
        if (enabled) {
            val shown = extras.take(EXTRAS_PREVIEW)
            Text(
                shown.joinToString("\n") { "• " + (it.relPath?.let(SyncPaths::baseName) ?: it.title) } +
                    if (extras.size > shown.size) "\n" + pluralStringResource(R.plurals.devicesync_extras_more, extras.size - shown.size, extras.size - shown.size) else "",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onErrorContainer,
            )
        }
    }
}

private const val EXTRAS_PREVIEW = 5

private fun LazyListScope.sectionItems(section: KindSection, plan: TransferPlan, selected: Set<Int>, onSelect: (Set<Int>) -> Unit) {
    val kind = section.kind
    item(key = "k_$kind", contentType = "header") {
        val title = when (kind) {
            FileKind.Audio -> R.string.devicesync_section_audio
            FileKind.Lyrics -> R.string.devicesync_section_lyrics
            FileKind.Cover -> R.string.devicesync_section_covers
        }
        SectionHeader(stringResource(title), Modifier.padding(top = 12.dp), count = section.folders.sumOf { it.items.size })
    }
    if (kind == FileKind.Audio) {
        item(key = "chips", contentType = "chips") { FormatChips(plan, selected, onSelect) }
    }
    section.folders.forEach { group ->
        item(key = "f_${kind}_${group.folder}", contentType = "folder") {
            FolderRow(group, selected) { onSelect(toggleGroup(selected, group.items)) }
        }
        items(group.items, key = { "i_${it.file.id}" }, contentType = { "file" }) { item ->
            FileRow(item, checked = item.file.id in selected) { onSelect(toggle(selected, item)) }
        }
    }
}

@Composable
private fun DestinationCard(destination: Uri?, error: Boolean, onPickFolder: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val label = destination?.let { uri ->
        TreeUriPaths.treeUriToPath(uri)?.let { FolderDisplay.format(context, it) } ?: uri.lastPathSegment
    }
    DeviceSyncCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = cs.primary, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Folder, null, tint = cs.onPrimary, modifier = Modifier.padding(8.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.devicesync_destination_title), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(
                    label ?: stringResource(R.string.devicesync_destination_needed),
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                )
            }
            TextButton(onClick = onPickFolder, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (destination == null) R.string.devicesync_choose_folder else R.string.devicesync_change_folder))
            }
        }
        if (error) {
            Text(stringResource(R.string.devicesync_folder_not_writable), style = MaterialTheme.typography.bodyMedium, color = cs.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryChips(counts: PlanCounts) {
    val cs = MaterialTheme.colorScheme
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            pluralStringResource(R.plurals.devicesync_count_new, counts.new, counts.new) to cs.primaryContainer,
            pluralStringResource(R.plurals.devicesync_count_replace, counts.replace, counts.replace) to cs.tertiaryContainer,
            pluralStringResource(R.plurals.devicesync_count_present, counts.present, counts.present) to cs.surfaceContainerHighest,
        ).forEach { (text, color) ->
            Surface(shape = MaterialTheme.shapes.small, color = color) {
                Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatChips(plan: TransferPlan, selected: Set<Int>, onSelect: (Set<Int>) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        formatChips(plan, selected).forEach { chip ->
            FilterChip(
                selected = chip.selected,
                enabled = chip.enabled,
                onClick = { onSelect(toggleFormat(plan, selected, chip.extension)) },
                label = { Text(stringResource(R.string.devicesync_format_chip, chip.label, chip.count)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun FolderRow(group: FolderGroup, selected: Set<Int>, onToggle: () -> Unit) {
    val check = groupCheck(selected, group.items)
    val state = when (check) {
        GroupCheck.All -> ToggleableState.On
        GroupCheck.Some -> ToggleableState.Indeterminate
        GroupCheck.None, null -> ToggleableState.Off
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .triStateToggleable(state = state, enabled = check != null, role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = state, onClick = null, enabled = check != null)
        Spacer(Modifier.width(16.dp))
        Text(
            group.folder.ifEmpty { stringResource(R.string.devicesync_top_folder) },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FileRow(item: PlanItem, checked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val present = item.status == Status.Present
    val status = when (item.status) {
        Status.New -> stringResource(R.string.devicesync_status_new)
        Status.Replace -> replaceLabel(item).orEmpty()
        Status.Present -> stringResource(R.string.devicesync_status_present)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked && !present, enabled = !present, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .alpha(if (present) 0.38f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && !present, onCheckedChange = null, enabled = !present)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(SyncPaths.baseName(item.file.relPath), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Text(
                stringResource(R.string.devicesync_join, status, formatBytes(context, item.file.size)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.status == Status.Replace) cs.tertiary else cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StartBar(plan: TransferPlan, selected: Set<Int>, freeBytes: Long?, onStart: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val state = startState(plan, selected, freeBytes)
    val freed = plan.bytesFreedAfter(selected)
    Surface(color = cs.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!state.dataOnly) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (freeBytes == null) {
                            Icon(Icons.Rounded.Warning, null, tint = cs.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(spaceLine(context, plan.bytesNeeded(selected), freeBytes), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (freed > 0) {
                        Text(
                            stringResource(R.string.devicesync_space_freed, formatBytes(context, freed)),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                disabledReason(context, state)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error)
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onStart, enabled = state.enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (state.dataOnly) R.string.devicesync_data_only else R.string.devicesync_start))
            }
        }
    }
}
