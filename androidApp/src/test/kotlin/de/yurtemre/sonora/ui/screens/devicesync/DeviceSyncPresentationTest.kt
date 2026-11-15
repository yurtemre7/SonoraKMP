package de.yurtemre.sonora.ui.screens.devicesync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.yurtemre.sonora.R
import de.yurtemre.sonora.devicesync.DeviceSyncFailure
import de.yurtemre.sonora.devicesync.DiscoveredSender
import de.yurtemre.sonora.devicesync.SyncTarget
import de.yurtemre.sonora.shared.devicesync.AudioMeta
import de.yurtemre.sonora.shared.devicesync.DeviceSyncClient
import de.yurtemre.sonora.shared.devicesync.DeviceSyncServer
import de.yurtemre.sonora.shared.devicesync.FileKind
import de.yurtemre.sonora.shared.devicesync.ManifestFile
import de.yurtemre.sonora.shared.devicesync.PlanItem
import de.yurtemre.sonora.shared.devicesync.ReceiverSong
import de.yurtemre.sonora.shared.devicesync.Status
import de.yurtemre.sonora.shared.devicesync.TransferPlan
import de.yurtemre.sonora.shared.devicesync.TransferProgress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceSyncPresentationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun file(id: Int, path: String, size: Long = 100, kind: FileKind = FileKind.Audio) =
        ManifestFile(id, path, size, 0, kind, if (kind == FileKind.Audio) AudioMeta("T$id", "A", "B", 1000) else null)

    private fun item(id: Int, path: String, status: Status = Status.New, size: Long = 100, kind: FileKind = FileKind.Audio, oldExt: String = "mp3") =
        PlanItem(
            file(id, path, size, kind), status,
            replaces = if (status == Status.Replace) ReceiverSong(90L + id, null, "T", "A", "B", 1000, 40, oldExt) else null,
        )

    private val mixed = TransferPlan(
        listOf(
            item(0, "B/two.mp3"),
            item(1, "A/one.flac", Status.Replace),
            item(2, "A/old.mp3", Status.Present),
            item(3, "A/one.lrc", kind = FileKind.Lyrics),
            item(4, "A/cover.jpg", kind = FileKind.Cover),
            item(5, "top.mp3"),
            item(6, "A/b.flac"),
        ),
    )

    // ---- code ------------------------------------------------------------------------------------------------------

    @Test fun codeIsGroupedThreePlusThree() {
        assertEquals("123 456", groupCode("123456"))
        assertEquals("12", groupCode("12"))
    }

    @Test fun codeIsSpokenDigitByDigit() {
        assertEquals("1 2 3 4 5 6", spokenDigits("123456"))
        assertEquals("", spokenDigits(""))
    }

    @Test fun codeInputKeepsSixDigitsOnly() {
        assertEquals("123456", codeInput("12a3 45678"))
        assertEquals("", codeInput("abc"))
    }

    @Test fun countdownRoundsUpAndStopsAtZero() {
        assertEquals("5:00", countdownText(expiresAtMs = 300_000, nowMs = 0))
        assertEquals("0:01", countdownText(expiresAtMs = 1_000, nowMs = 1))
        assertEquals("0:00", countdownText(expiresAtMs = 1_000, nowMs = 5_000))
    }

    // ---- receiver connect --------------------------------------------------------------------------------------

    private val s1 = DiscoveredSender("Sonora-aaaa", "Pixel", "192.168.1.2", 4000)
    private val s2 = DiscoveredSender("Sonora-bbbb", "Galaxy", "192.168.1.3", 4001)

    @Test fun singleSenderIsAutoSelected() {
        assertEquals(s1, effectiveSender(listOf(s1), null))
        assertNull(effectiveSender(listOf(s1, s2), null))
        assertEquals(s2, effectiveSender(listOf(s1, s2), "Sonora-bbbb"))
        assertEquals(s1, effectiveSender(listOf(s1), "Sonora-gone"), "a vanished choice falls back to the only sender")
        assertNull(effectiveSender(emptyList(), null))
    }

    @Test fun manualAddressOpensWhenDiscoveryFailsOrFindsNothing() {
        assertTrue(manualExpandedByDefault(discoveryFailed = true, senderCount = 3, searchTimedOut = false))
        assertTrue(manualExpandedByDefault(discoveryFailed = false, senderCount = 0, searchTimedOut = true))
        assertFalse(manualExpandedByDefault(discoveryFailed = false, senderCount = 0, searchTimedOut = false))
        assertFalse(manualExpandedByDefault(discoveryFailed = false, senderCount = 1, searchTimedOut = true))
    }

    @Test fun connectActionPrefersATypedAddress() {
        assertEquals(ConnectAction.Connect(SyncTarget("10.0.0.5", 41234)), connectAction(true, " 10.0.0.5:41234 ", s1))
        assertEquals(ConnectAction.InvalidAddress, connectAction(true, "10.0.0.5", s1))
        assertEquals(ConnectAction.Connect(SyncTarget.of(s1)), connectAction(true, "  ", s1))
        assertEquals(ConnectAction.Connect(SyncTarget.of(s1)), connectAction(false, "junk", s1))
        assertEquals(ConnectAction.NeedSender, connectAction(false, "", null))
    }

    @Test fun everyFailureHasAMessage() {
        val messages = DeviceSyncFailure.entries.map { context.getString(failureMessage(it)) }
        assertEquals(DeviceSyncFailure.entries.size, messages.toSet().size)
        assertEquals("Code didn't match. Check the code and try again.", context.getString(failureMessage(DeviceSyncFailure.CodeMismatch)))
    }

    @Test fun failedFileReasonsAreLocalized() {
        assertEquals(R.string.devicesync_file_failed_damaged, failedFileReason(DeviceSyncClient.FAILURE_CHECKSUM))
        assertEquals(R.string.devicesync_file_failed_damaged, failedFileReason(DeviceSyncClient.FAILURE_SIZE))
        assertEquals(R.string.devicesync_file_failed_write, failedFileReason(DeviceSyncClient.FAILURE_WRITE))
        assertEquals(R.string.devicesync_file_failed_read, failedFileReason(DeviceSyncServer.ERROR_READ_FAILED))
        assertEquals(R.string.devicesync_file_failed_read, failedFileReason(DeviceSyncServer.ERROR_UNKNOWN_FILE))
        assertEquals(R.string.devicesync_file_failed_other, failedFileReason("something_new"))
    }

    @Test fun typedManualAddressKeepsTheFieldOpen() {
        // A sender showing up after the timeout must not collapse an address the user typed.
        assertTrue(isManualOpen(toggled = null, manualText = "10.0.0.5:4", discoveryFailed = false, senderCount = 1, searchTimedOut = true))
        assertTrue(isManualOpen(toggled = false, manualText = "10.0.0.5:4", discoveryFailed = false, senderCount = 1, searchTimedOut = true))
        assertFalse(isManualOpen(toggled = null, manualText = "", discoveryFailed = false, senderCount = 1, searchTimedOut = true))
        assertTrue(isManualOpen(toggled = true, manualText = "", discoveryFailed = false, senderCount = 1, searchTimedOut = false))
        assertFalse(isManualOpen(toggled = false, manualText = " ", discoveryFailed = true, senderCount = 0, searchTimedOut = true))
    }

    // ---- selection ---------------------------------------------------------------------------------------------

    @Test fun summaryCounts() {
        assertEquals(PlanCounts(new = 5, replace = 1, present = 1), planCounts(mixed))
        assertFalse(isAlreadySynced(mixed))
        assertTrue(isAlreadySynced(TransferPlan(listOf(item(0, "a.mp3", Status.Present)))))
        assertTrue(isAlreadySynced(TransferPlan(emptyList())))
    }

    @Test fun sectionsByKindThenFolder() {
        val sections = sections(mixed)
        assertEquals(listOf(FileKind.Audio, FileKind.Lyrics, FileKind.Cover), sections.map { it.kind })
        val audio = sections.first()
        assertEquals(listOf("", "A", "B"), audio.folders.map { it.folder })
        assertEquals(listOf("A/b.flac", "A/old.mp3", "A/one.flac"), audio.folders[1].items.map { it.file.relPath })
        assertEquals(listOf(FileKind.Audio), sections(TransferPlan(listOf(item(0, "a.mp3")))).map { it.kind })
    }

    @Test fun foldersDifferingOnlyByCaseStaySeparate() {
        val plan = TransferPlan(listOf(item(0, "music/a/x.mp3"), item(1, "Music/A/y.mp3"), item(2, "Music/B/z.mp3")))
        val folders = sections(plan).single().folders
        assertEquals(listOf("Music/A", "music/a", "Music/B"), folders.map { it.folder })
        assertEquals(3, folders.sumOf { it.items.size }, "no file disappears")
    }

    @Test fun reconcileKeepsTheEditedSelectionAcrossReplans() {
        assertEquals(mixed.defaultSelection(), reconcileSelection(null, mixed), "first plan: defaults")
        assertEquals(setOf(0, 3), reconcileSelection(setOf(0, 3), mixed))
        // ids that became Present (or vanished) drop out; nothing new is added.
        val replanned = TransferPlan(listOf(item(0, "B/two.mp3", Status.Present), item(3, "A/one.lrc", kind = FileKind.Lyrics), item(5, "top.mp3")))
        assertEquals(setOf(3), reconcileSelection(setOf(0, 3, 4), replanned))
        assertEquals(emptySet(), reconcileSelection(emptySet(), mixed), "select none stays none")
    }

    @Test fun defaultSelectionIsEverythingNewAndReplace() {
        assertEquals(setOf(0, 1, 3, 4, 5, 6), mixed.defaultSelection())
        assertEquals(mixed.defaultSelection(), selectAll(mixed))
    }

    @Test fun selectAllNoneAndToggles() {
        val all = selectAll(mixed)
        assertEquals(all - 0, toggle(all, mixed.items[0]))
        assertEquals(all, toggle(all - 0, mixed.items[0]))
        assertEquals(all, toggle(all, mixed.items[2]), "Present rows cannot be selected")

        val folderA = sections(mixed).first().folders[1].items
        assertEquals(GroupCheck.All, groupCheck(all, folderA))
        val none = toggleGroup(all, folderA)
        assertEquals(all - setOf(1, 6), none)
        assertEquals(GroupCheck.None, groupCheck(none, folderA))
        assertEquals(GroupCheck.Some, groupCheck(all - 6, folderA))
        assertEquals(all, toggleGroup(all - 6, folderA), "partly selected → select the rest")
        assertNull(groupCheck(all, listOf(mixed.items[2])), "nothing selectable")
    }

    @Test fun formatChipsCountAndToggle() {
        val all = selectAll(mixed)
        val chips = formatChips(mixed, all)
        assertEquals(listOf("MP3", "FLAC"), chips.map { it.label }, "most files first, then by name")
        assertEquals(listOf(3, 2), chips.map { it.count })
        assertTrue(chips.all { it.selected })
        val mp3 = chips.single { it.label == "MP3" }
        val withoutMp3 = toggleFormat(mixed, all, mp3.extension)
        assertEquals(all - setOf(0, 5), withoutMp3)
        assertFalse(formatChips(mixed, withoutMp3).single { it.label == "MP3" }.selected)
        assertEquals(all, toggleFormat(mixed, withoutMp3, "mp3"))

        val onlyPresent = TransferPlan(listOf(item(0, "a.ogg", Status.Present)))
        assertFalse(formatChips(onlyPresent, emptySet()).single().enabled)
    }

    @Test fun replaceRowsShowOldAndNewFormat() {
        assertEquals("MP3 → FLAC", replaceLabel(mixed.items[1]))
        assertNull(replaceLabel(mixed.items[0]))
    }

    // ---- start button and space -------------------------------------------------------------------------------

    private val margin = TransferPlan.SAFETY_MARGIN_BYTES

    @Test fun startIsDataOnlyWhenNothingIsSelected() {
        val s = startState(mixed, emptySet(), freeBytes = 0)
        assertTrue(s.dataOnly)
        assertTrue(s.enabled, "never blocked for space")
        assertEquals(0L, s.shortBytes)
        assertTrue(startState(mixed, setOf(2), freeBytes = 0).dataOnly, "Present ids count as nothing")
    }

    @Test fun startIsBlockedWhenTheSelectionDoesNotFit() {
        val sel = setOf(0, 5) // 200 bytes
        assertEquals(StartState(dataOnly = false, enabled = true, shortBytes = 0), startState(mixed, sel, 200 + margin))
        assertEquals(StartState(dataOnly = false, enabled = false, shortBytes = 1), startState(mixed, sel, 199 + margin))
        assertTrue(startState(mixed, sel, freeBytes = null).enabled, "unknown free space is allowed")
    }

    @Test fun spaceLineTexts() {
        val size = { b: Long -> "${b}B" }
        assertEquals("Needs 200B · 5B free", spaceLine(context, needed = 200, free = 5, size = size))
        assertEquals("Needs 200B · free space unknown", spaceLine(context, needed = 200, free = null, size = size))
        assertEquals("Not enough space: free up 7B more", disabledReason(context, StartState(false, false, 7), size))
        assertNull(disabledReason(context, StartState(false, true, 0), size))
    }

    @Test fun bytesUseTheAppFileSizeFormatter() {
        assertEquals(android.text.format.Formatter.formatShortFileSize(context, 3_200_000_000), formatBytes(context, 3_200_000_000))
    }

    // ---- progress -----------------------------------------------------------------------------------------------

    @Test fun progressFractionAndEta() {
        val p = TransferProgress(1, 0, 0, totalBytes = 250, totalSize = 1000, filesDone = 1, filesTotal = 4, bytesPerSecond = 50)
        assertEquals(0.25f, progressFraction(p))
        assertEquals(15L, etaSeconds(p))
        assertNull(etaSeconds(p.copy(bytesPerSecond = 0)))
        assertEquals(0f, progressFraction(p.copy(totalSize = 0)))
    }
}
