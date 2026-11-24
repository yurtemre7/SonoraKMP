package de.yurtemre.sonora.ui.screens.jam

import de.yurtemre.sonora.jam.DiscoveredJam
import de.yurtemre.sonora.jam.JamEndReason
import de.yurtemre.sonora.jam.JamGuestUi
import de.yurtemre.sonora.jam.JamQueueItem
import de.yurtemre.sonora.jam.JamRejoin
import de.yurtemre.sonora.jam.JamSyncState
import de.yurtemre.sonora.jam.JamUiState
import de.yurtemre.sonora.shared.jam.JAM_PROTOCOL_VERSION
import de.yurtemre.sonora.shared.jam.JamError
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.jam.JamSong
import de.yurtemre.sonora.testing.testSong
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JamViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val jam = FakeJamControls()
    private val found = MutableStateFlow<List<DiscoveredJam>>(emptyList())
    private var discovery: () -> Flow<List<DiscoveredJam>> = { found }

    private val livingRoom = DiscoveredJam("SonoraJam-ab12", "Living room phone", "192.168.1.20", 41_234, JAM_PROTOCOL_VERSION)
    private val kitchen = DiscoveredJam("SonoraJam-cd34", "Kitchen phone", "192.168.1.21", 41_000, JAM_PROTOCOL_VERSION)
    private val oldApp = DiscoveredJam("SonoraJam-ef56", "Old phone", "192.168.1.22", 41_001, JAM_PROTOCOL_VERSION + 1)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): JamViewModel =
        JamViewModel(jam, { discovery() }, nowMs = { testScheduler.currentTime }).also {
            backgroundScope.launch { it.join.collect {} }
            backgroundScope.launch { it.screen.collect {} }
            runCurrent()
        }

    private fun TestScope.openJoin(vm: JamViewModel) {
        vm.openJoin()
        vm.startDiscovery()
        runCurrent()
    }

    // ---- join: discovery and manual address -------------------------------------------------------------------------

    @Test fun joinScreenListsDiscoveredJamsWithHostNames() = runTest(dispatcher) {
        val vm = vm()
        openJoin(vm)
        found.value = listOf(livingRoom, kitchen)
        runCurrent()
        assertEquals(JamScreen.Join, vm.screen.value)
        assertEquals(listOf("Living room phone", "Kitchen phone"), vm.join.value.jams.map { it.jam.hostName })
        assertNull(vm.join.value.selected) // two found: the guest picks one
        vm.select(kitchen.serviceName)
        vm.setCode("654321")
        runCurrent()
        assertEquals(listOf<Any>("192.168.1.21", 41_000, "654321", "Kitchen phone"), jam.joins.single())
    }

    @Test fun theOnlyJamFoundIsSelectedAndTheSixthDigitJoins() = runTest(dispatcher) {
        val vm = vm()
        openJoin(vm)
        found.value = listOf(livingRoom)
        runCurrent()
        assertEquals(livingRoom.serviceName, vm.join.value.selected)
        vm.setCode("12a34")
        assertEquals("1234", vm.join.value.code)
        assertTrue(jam.joins.isEmpty())
        vm.setCode("123456")
        runCurrent()
        assertEquals(listOf<Any>("192.168.1.20", 41_234, "123456", "Living room phone"), jam.joins.single())
    }

    @Test fun aJamWithAnotherVersionIsListedButNotSelectable() = runTest(dispatcher) {
        val vm = vm()
        openJoin(vm)
        found.value = listOf(oldApp)
        runCurrent()
        assertFalse(vm.join.value.jams.single().compatible)
        assertNull(vm.join.value.selected)
        vm.select(oldApp.serviceName)
        assertNull(vm.join.value.selected)
    }

    @Test fun nothingFoundOpensTheManualAddressAfterTheSearchTime() = runTest(dispatcher) {
        val vm = vm()
        openJoin(vm)
        assertFalse(vm.join.value.manualOpen)
        advanceTimeBy(JamViewModel.SEARCH_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(vm.join.value.searchTimedOut)
        assertTrue(vm.join.value.manualOpen)
    }

    @Test fun failedDiscoveryOpensTheManualAddressAtOnce() = runTest(dispatcher) {
        discovery = { flow { throw IOException("discovery_failed:0") } }
        val vm = vm()
        openJoin(vm)
        assertTrue(vm.join.value.discoveryFailed)
        assertTrue(vm.join.value.manualOpen)
    }

    @Test fun manualHostAndPortJoin() = runTest(dispatcher) {
        val vm = vm()
        openJoin(vm)
        found.value = listOf(livingRoom)
        runCurrent()
        vm.setManualText("192.168.1.50:4000") // a typed address wins over the found Jam
        vm.setCode("123456")
        runCurrent()
        assertEquals(listOf<Any>("192.168.1.50", 4000, "123456", "192.168.1.50"), jam.joins.single())
    }

    @Test fun badManualAddressIsFlaggedAndNotJoined() = runTest(dispatcher) {
        val vm = vm()
        openJoin(vm)
        vm.setManualText("192.168.1.50")
        vm.setCode("123456")
        runCurrent()
        assertTrue(vm.join.value.addressInvalid)
        assertTrue(jam.joins.isEmpty())
        vm.setManualText("192.168.1.50:4000")
        assertFalse(vm.join.value.addressInvalid)
    }

    @Test fun noJamChosenAsksToChooseOne() = runTest(dispatcher) {
        val vm = vm()
        openJoin(vm)
        found.value = listOf(livingRoom, kitchen)
        runCurrent()
        vm.setCode("123456")
        runCurrent()
        assertTrue(vm.join.value.needJam)
        assertTrue(jam.joins.isEmpty())
    }

    @Test fun joiningShowsTheSpinner() = runTest(dispatcher) {
        val vm = vm()
        openJoin(vm)
        found.value = listOf(livingRoom)
        runCurrent()
        vm.setCode("123456")
        runCurrent()
        assertTrue(vm.join.value.joining)
        assertEquals("Living room phone", vm.join.value.joiningName)
        assertFalse(vm.join.value.canSubmit)
    }

    // ---- join: failures ---------------------------------------------------------------------------------------------

    private fun TestScope.failJoin(vm: JamViewModel, error: JamError) {
        jam.joinResult = JamUiState.Error(error)
        openJoin(vm)
        found.value = listOf(livingRoom)
        runCurrent()
        vm.setCode("123456")
        runCurrent()
    }

    @Test fun wrongCodeIsShownAndTheCodeCleared() = runTest(dispatcher) {
        val vm = vm()
        failJoin(vm, JamError.WrongCode)
        assertEquals(JamScreen.Join, vm.screen.value)
        assertEquals(JamError.WrongCode, vm.join.value.error)
        assertEquals("", vm.join.value.code)
        assertEquals(livingRoom.serviceName, vm.join.value.selected)
    }

    @Test fun lockoutCountsDownAndBlocksJoiningUntilItIsOver() = runTest(dispatcher) {
        val vm = vm()
        failJoin(vm, JamError.Locked)
        assertEquals(JamError.Locked, vm.join.value.error)
        assertEquals(10, vm.join.value.lockSecondsLeft)
        jam.joinResult = null
        vm.setCode("111111")
        runCurrent()
        assertEquals(1, jam.joins.size) // refused while locked
        assertFalse(vm.join.value.canSubmit)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(7, vm.join.value.lockSecondsLeft)

        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(0, vm.join.value.lockSecondsLeft)
        assertNull(vm.join.value.error)
        assertTrue("dismiss" in jam.calls)
        assertEquals(JamScreen.Join, vm.screen.value)
        vm.setCode("")
        vm.setCode("111111")
        runCurrent()
        assertEquals(2, jam.joins.size)
    }

    @Test fun fullIsShown() = runTest(dispatcher) {
        val vm = vm()
        failJoin(vm, JamError.Full)
        assertEquals(JamError.Full, vm.join.value.error)
    }

    @Test fun versionMismatchAndCodeExhaustedAreShownWithTheirAction() = runTest(dispatcher) {
        val vm = vm()
        failJoin(vm, JamError.VersionMismatch)
        assertEquals(JamError.VersionMismatch, vm.join.value.error)
        assertEquals(JamErrorAction.UpdateApp, jamErrorAction(vm.join.value.error!!))

        jam.joinResult = JamUiState.Error(JamError.CodeExhausted)
        vm.setCode("123456")
        runCurrent()
        assertEquals(JamError.CodeExhausted, vm.join.value.error)
        assertEquals(JamErrorAction.AskForNewCode, jamErrorAction(vm.join.value.error!!))
    }

    // ---- guest in a Jam ---------------------------------------------------------------------------------------------

    private val song = JamSong("A", "Artist", "Album", 180_000)
    private fun joined(sync: JamSyncState = JamSyncState.InSync, canControl: Boolean = false, canAdd: Boolean = true, playing: Boolean = true) =
        JamUiState.Joined("Host phone", listOf(JamQueueItem(song, true)), 0, playing, canAdd, canControl, sync)

    @Test fun hostDropShowsJamEndedOnlyAfterTheCurrentSongEnds() = runTest(dispatcher) {
        val vm = vm()
        jam.state.value = joined()
        runCurrent()
        assertEquals(JamScreen.Guest, vm.screen.value)
        // Host gone: the song plays to its end, still the guest screen ("ending after this song").
        jam.state.value = joined(sync = JamSyncState.Ending)
        runCurrent()
        assertEquals(JamScreen.Guest, vm.screen.value)
        // The controller reports Ended once the song is over.
        jam.state.value = JamUiState.Ended(JamEndReason.HostGone)
        runCurrent()
        assertEquals(JamScreen.Ended, vm.screen.value)
    }

    @Test fun rejoinAfterAWifiChangeJoinsTheSameHostWithANewCode() = runTest(dispatcher) {
        val vm = vm()
        jam.state.value = JamUiState.Ended(JamEndReason.NetworkChanged, JamRejoin("192.168.1.20", 41_234, "Living room phone"))
        runCurrent()
        vm.rejoin()
        runCurrent()
        assertTrue("dismiss" in jam.calls)
        assertEquals(JamScreen.Join, vm.screen.value)
        assertEquals("Living room phone", vm.join.value.rejoinName)
        vm.setCode("222333")
        runCurrent()
        assertEquals(listOf<Any>("192.168.1.20", 41_234, "222333", "Living room phone"), jam.joins.single())
    }

    @Test fun guestSkipAndPauseOnlyWhenAllowedAndMappedToRequests() {
        val actions = JamGuestActions(jam)
        actions.next(joined(canControl = false))
        actions.playPause(joined(canControl = false))
        assertTrue(jam.calls.isEmpty())
        actions.next(joined(canControl = true))
        actions.previous(joined(canControl = true))
        actions.playPause(joined(canControl = true, playing = true))
        actions.playPause(joined(canControl = true, playing = false))
        assertEquals(listOf("next", "previous", "pause", "play"), jam.calls)
    }

    @Test fun guestAddsASongOnlyWhenAllowed() {
        val actions = JamGuestActions(jam)
        val s = testSong(5, "Five")
        assertFalse(actions.add(joined(canAdd = false), s))
        assertTrue(actions.add(joined(canAdd = true), s))
        assertEquals(listOf(s), jam.added)
    }

    @Test fun guestLeaves() = runTest(dispatcher) {
        val vm = vm()
        jam.state.value = joined()
        runCurrent()
        vm.leave()
        runCurrent()
        assertTrue("leave" in jam.calls)
        assertEquals(JamScreen.Start, vm.screen.value)
    }

    // ---- host -------------------------------------------------------------------------------------------------------

    @Test fun startingAJamHostsWithTheChosenPermissionsOnce() = runTest(dispatcher) {
        val vm = vm()
        val settings = JamSettings(guestsCanAdd = false, guestsCanControl = true)
        vm.startHosting(settings)
        runCurrent()
        vm.startHosting(settings)
        runCurrent()
        assertEquals(listOf(settings), jam.hosts)
        assertEquals(JamScreen.Host, vm.screen.value)
    }

    @Test fun hostingThatCannotStartShowsTheFailure() = runTest(dispatcher) {
        jam.hostFails = true
        val vm = vm()
        vm.startHosting(JamSettings())
        runCurrent()
        assertEquals(JamScreen.HostFailed, vm.screen.value)
    }

    private fun hosting(vararg guests: JamGuestUi, exhausted: Boolean = false) =
        JamUiState.Hosting("123456", 41_234, guests.toList(), JamSettings(), codeExhausted = exhausted)

    @Test fun removingAGuestAsksFirst() = runTest(dispatcher) {
        val vm = vm()
        val anna = JamGuestUi("g1", "Anna's phone")
        jam.state.value = hosting(anna)
        runCurrent()
        vm.requestKick(anna)
        assertEquals(JamConfirm.Kick(anna), vm.confirm.value)
        assertTrue(jam.calls.isEmpty())
        vm.dismissConfirm()
        assertNull(vm.confirm.value)
        assertTrue(jam.calls.isEmpty())
        vm.requestKick(anna)
        vm.confirm()
        assertEquals(listOf("kick=g1"), jam.calls)
        assertNull(vm.confirm.value)
    }

    @Test fun aGuestWhoLeftIsNoLongerOfferedForRemoval() = runTest(dispatcher) {
        val vm = vm()
        val anna = JamGuestUi("g1", "Anna's phone")
        jam.state.value = hosting(anna)
        runCurrent()
        vm.requestKick(anna)
        jam.state.value = hosting()
        runCurrent()
        assertNull(vm.confirm.value)
    }

    @Test fun endingTheJamAsksFirst() = runTest(dispatcher) {
        val vm = vm()
        jam.state.value = hosting()
        runCurrent()
        vm.requestEnd()
        assertEquals(JamConfirm.End, vm.confirm.value)
        assertTrue(jam.calls.isEmpty())
        vm.confirm()
        runCurrent()
        assertEquals(listOf("endJam"), jam.calls)
        assertEquals(JamScreen.Start, vm.screen.value)
    }

    @Test fun permissionsAndNewCodeGoToTheController() = runTest(dispatcher) {
        val vm = vm()
        jam.state.value = hosting(exhausted = true)
        runCurrent()
        vm.setGuestsCanAdd(false)
        vm.setGuestsCanControl(true)
        vm.newCode()
        assertEquals(listOf("canAdd=false", "canControl=true", "newCode"), jam.calls)
    }
}
