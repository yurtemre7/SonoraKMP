package de.yurtemre.sonora.ui.screens.devicesync

import android.content.Context
import androidx.annotation.StringRes
import de.yurtemre.sonora.R
import de.yurtemre.sonora.devicesync.DeviceSyncDiscovery
import de.yurtemre.sonora.devicesync.DeviceSyncFailure
import de.yurtemre.sonora.devicesync.DiscoveredSender
import de.yurtemre.sonora.devicesync.SyncTarget
import de.yurtemre.sonora.shared.devicesync.DeviceSyncClient
import de.yurtemre.sonora.shared.devicesync.DeviceSyncServer
import de.yurtemre.sonora.shared.devicesync.FileKind
import de.yurtemre.sonora.shared.devicesync.PlanItem
import de.yurtemre.sonora.shared.devicesync.Status
import de.yurtemre.sonora.shared.devicesync.SyncPaths
import de.yurtemre.sonora.shared.devicesync.TransferPlan
import de.yurtemre.sonora.shared.devicesync.TransferProgress
import de.yurtemre.sonora.ui.format.DurationFormat
import de.yurtemre.sonora.ui.format.SizeFormat

// Pure presentation logic of the device sync screens (unit-tested in DeviceSyncPresentationTest).

const val CODE_LENGTH = 6

/** "123456" → "123 456" (3+3); anything else unchanged. */
fun groupCode(code: String): String =
    if (code.length == CODE_LENGTH) code.substring(0, 3) + " " + code.substring(3) else code

/** Digits separated by spaces, so screen readers read them one by one. */
fun spokenDigits(code: String): String = code.toList().joinToString(" ")

/** What the code field keeps of typed or pasted text: digits only, at most [CODE_LENGTH]. */
fun codeInput(raw: String): String = raw.filter { it in '0'..'9' }.take(CODE_LENGTH)

/** Remaining code lifetime as `m:ss`, rounded up, never negative. */
fun countdownText(expiresAtMs: Long, nowMs: Long): String =
    DurationFormat.mss(((expiresAtMs - nowMs + 999) / 1000).coerceAtLeast(0))

// ---- receiver: connect ---------------------------------------------------------------------------------------

/** R3: the sender the user picked while it is still listed, else the only one found, else none. */
fun effectiveSender(senders: List<DiscoveredSender>, selectedServiceName: String?): DiscoveredSender? =
    senders.firstOrNull { it.serviceName == selectedServiceName } ?: senders.singleOrNull()

/** The manual `IP:port` field starts expanded when mDNS failed, or nothing was found within the search time. */
fun manualExpandedByDefault(discoveryFailed: Boolean, senderCount: Int, searchTimedOut: Boolean): Boolean =
    discoveryFailed || (searchTimedOut && senderCount == 0)

sealed interface ConnectAction {
    data class Connect(val target: SyncTarget) : ConnectAction
    data object InvalidAddress : ConnectAction
    data object NeedSender : ConnectAction
}

/**
 * Whether the manual `IP:port` field is open: always once the user typed an address (it is then used), else the
 * user's toggle, else [manualExpandedByDefault].
 */
fun isManualOpen(toggled: Boolean?, manualText: String, discoveryFailed: Boolean, senderCount: Int, searchTimedOut: Boolean): Boolean =
    manualText.isNotBlank() || (toggled ?: manualExpandedByDefault(discoveryFailed, senderCount, searchTimedOut))

/** A typed address (when the manual field is open and not blank) wins over the selected sender. */
fun connectAction(manualExpanded: Boolean, manualText: String, sender: DiscoveredSender?): ConnectAction {
    if (manualExpanded && manualText.isNotBlank()) {
        val (host, port) = DeviceSyncDiscovery.parseManual(manualText) ?: return ConnectAction.InvalidAddress
        return ConnectAction.Connect(SyncTarget(host, port))
    }
    return sender?.let { ConnectAction.Connect(SyncTarget.of(it)) } ?: ConnectAction.NeedSender
}

@StringRes
fun failureMessage(reason: DeviceSyncFailure): Int = when (reason) {
    DeviceSyncFailure.CodeMismatch -> R.string.devicesync_failure_code
    DeviceSyncFailure.VersionMismatch -> R.string.devicesync_failure_version
    DeviceSyncFailure.Network -> R.string.devicesync_failure_network
    DeviceSyncFailure.Protocol -> R.string.devicesync_failure_protocol
    DeviceSyncFailure.Storage -> R.string.devicesync_failure_storage
    DeviceSyncFailure.DataApply -> R.string.devicesync_failure_data
    DeviceSyncFailure.SenderStart -> R.string.devicesync_failure_sender
    DeviceSyncFailure.Unknown -> R.string.devicesync_failure_unknown
}

/** Per-file failure codes: the client's checks and write failures, and the sender's error frames. */
@StringRes
fun failedFileReason(reason: String): Int = when (reason) {
    DeviceSyncClient.FAILURE_SIZE, DeviceSyncClient.FAILURE_CHECKSUM -> R.string.devicesync_file_failed_damaged
    DeviceSyncClient.FAILURE_WRITE -> R.string.devicesync_file_failed_write
    DeviceSyncServer.ERROR_READ_FAILED, DeviceSyncServer.ERROR_UNKNOWN_FILE -> R.string.devicesync_file_failed_read
    else -> R.string.devicesync_file_failed_other
}

// ---- receiver: selection -------------------------------------------------------------------------------------

data class PlanCounts(val new: Int, val replace: Int, val present: Int)

fun planCounts(plan: TransferPlan): PlanCounts = PlanCounts(
    new = plan.items.count { it.status == Status.New },
    replace = plan.items.count { it.status == Status.Replace },
    present = plan.items.count { it.status == Status.Present },
)

/** Nothing to copy: every file is already here (or the sender has none). */
fun isAlreadySynced(plan: TransferPlan): Boolean = plan.items.none { it.status != Status.Present }

data class FolderGroup(val folder: String, val items: List<PlanItem>)
data class KindSection(val kind: FileKind, val folders: List<FolderGroup>)

/** DSYNC-09: Audio, Lyrics, Covers (empty ones left out), then folders and file names in name order. */
fun sections(plan: TransferPlan): List<KindSection> {
    val byKind = plan.groupsByKind()
    return FileKind.entries.mapNotNull { kind ->
        val items = byKind[kind].orEmpty().ifEmpty { return@mapNotNull null }
        // Grouped by exact path (folders differing only by case stay separate), sorted ignoring case.
        val folders = items.groupBy { SyncPaths.parent(it.file.relPath) }.entries
            .sortedWith(compareBy<Map.Entry<String, *>, String>(String.CASE_INSENSITIVE_ORDER) { it.key }.thenBy { it.key })
            .map { (folder, list) -> FolderGroup(folder, list.sortedWith(byPath)) }
        KindSection(kind, folders)
    }
}

private val byPath = compareBy<PlanItem, String>(String.CASE_INSENSITIVE_ORDER) { it.file.relPath }.thenBy { it.file.relPath }

private fun selectableIds(items: Iterable<PlanItem>): Set<Int> =
    items.filter { it.status != Status.Present }.mapTo(LinkedHashSet()) { it.file.id }

/*
 * Selection across re-plans: the screen stores only the ids the user deselected. Everything else follows the
 * plan's defaults, so a re-plan (same or another folder) keeps the user's edits and still selects files that
 * became New or Replace.
 */

/** The selection for [plan]: its defaults minus what the user deselected. */
fun selectionOf(plan: TransferPlan, deselected: Set<Int>): Set<Int> = plan.defaultSelection() - deselected

/** Deselected ids after the user changed [plan]'s selection to [newSelected] (a toggle, group, chip or Select all). */
fun deselectedAfter(plan: TransferPlan, deselected: Set<Int>, newSelected: Set<Int>): Set<Int> =
    (deselected - newSelected) + (selectableIds(plan.items) - newSelected)

/** Select none: every file of [plan], so files that turn New in a later re-plan stay unselected too. */
fun selectNone(plan: TransferPlan, deselected: Set<Int>): Set<Int> = deselected + plan.items.map { it.file.id }

/** Select all = every New and Replace item (the default selection). */
fun selectAll(plan: TransferPlan): Set<Int> = plan.defaultSelection()

fun toggle(selected: Set<Int>, item: PlanItem): Set<Int> = when {
    item.status == Status.Present -> selected
    item.file.id in selected -> selected - item.file.id
    else -> selected + item.file.id
}

enum class GroupCheck { All, Some, None }

/** Check state of a folder or format group; null when it has nothing selectable. */
fun groupCheck(selected: Set<Int>, items: List<PlanItem>): GroupCheck? {
    val ids = selectableIds(items).ifEmpty { return null }
    val n = ids.count { it in selected }
    return when (n) {
        ids.size -> GroupCheck.All
        0 -> GroupCheck.None
        else -> GroupCheck.Some
    }
}

/** Fully selected group → deselect it; otherwise select all of it. */
fun toggleGroup(selected: Set<Int>, items: List<PlanItem>): Set<Int> {
    val ids = selectableIds(items)
    return if (selected.containsAll(ids)) selected - ids else selected + ids
}

/** [extension] lowercase; [label] uppercase; [selected]: all its selectable files are selected. */
data class FormatChip(val extension: String, val label: String, val count: Int, val selected: Boolean, val enabled: Boolean)

/** Audio formats with file counts (all statuses), most files first, then by name. */
fun formatChips(plan: TransferPlan, selected: Set<Int>): List<FormatChip> {
    val byExt = plan.items.filter { it.file.kind == FileKind.Audio }.groupBy { SyncPaths.extension(it.file.relPath) }
    return plan.audioFormats().entries
        .sortedWith(compareByDescending<Map.Entry<String, *>> { byExt[it.key].orEmpty().size }.thenBy { it.key })
        .map { (ext, stat) ->
            val check = groupCheck(selected, byExt[ext].orEmpty())
            FormatChip(ext, ext.uppercase(), stat.count, selected = check == GroupCheck.All, enabled = check != null)
        }
}

fun toggleFormat(plan: TransferPlan, selected: Set<Int>, extension: String): Set<Int> =
    toggleGroup(selected, plan.items.filter { it.file.kind == FileKind.Audio && SyncPaths.extension(it.file.relPath) == extension })

/** Replace rows: "MP3 → FLAC" (old → new extension); null for other rows. */
fun replaceLabel(item: PlanItem): String? {
    val old = item.replaces?.takeIf { item.status == Status.Replace } ?: return null
    return old.extension.uppercase() + " → " + SyncPaths.extension(item.file.relPath).uppercase()
}

/**
 * The primary button. [dataOnly]: nothing selected to copy, so the button reads "Sync data only" and is never
 * blocked for space. [shortBytes]: how much more room the selection needs (0 when it fits).
 */
data class StartState(val dataOnly: Boolean, val enabled: Boolean, val shortBytes: Long)

fun startState(plan: TransferPlan, selected: Set<Int>, freeBytes: Long?): StartState {
    val needed = plan.bytesNeeded(selected)
    val dataOnly = plan.items.none { it.file.id in selected && it.status != Status.Present }
    val fits = dataOnly || freeBytes == null || plan.fits(selected, freeBytes)
    val short = if (fits) 0L else needed + TransferPlan.SAFETY_MARGIN_BYTES - freeBytes
    return StartState(dataOnly, fits, short)
}

fun formatBytes(context: Context, bytes: Long): String = SizeFormat.fileSize(context, bytes)

/** "Needs 3.2 GB · 18.4 GB free", or "… · free space unknown". */
fun spaceLine(context: Context, needed: Long, free: Long?, size: (Long) -> String = { formatBytes(context, it) }): String =
    if (free == null) context.getString(R.string.devicesync_space_unknown, size(needed))
    else context.getString(R.string.devicesync_space_needed, size(needed), size(free))

/** Why Start is disabled; null when it is enabled. */
fun disabledReason(context: Context, state: StartState, size: (Long) -> String = { formatBytes(context, it) }): String? =
    if (state.enabled) null else context.getString(R.string.devicesync_not_enough_space, size(state.shortBytes))

// ---- progress ------------------------------------------------------------------------------------------------

fun progressFraction(p: TransferProgress): Float =
    if (p.totalSize <= 0) 0f else (p.totalBytes.toFloat() / p.totalSize).coerceIn(0f, 1f)

/** Seconds left at the current speed; null while the speed is unknown. */
fun etaSeconds(p: TransferProgress): Long? =
    if (p.bytesPerSecond <= 0) null else (p.totalSize - p.totalBytes).coerceAtLeast(0) / p.bytesPerSecond
