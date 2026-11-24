package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class JamSpeakerDelayTest {
    @Test fun settingChangesReachTheController() = runTest {
        val settings = MutableStateFlow(AppSettings())
        val delay = jamSpeakerDelay(settings, backgroundScope)
        runCurrent()
        assertEquals(0, delay())
        settings.value = settings.value.copy(jamSpeakerDelayMs = 120)
        runCurrent()
        assertEquals(120, delay())
        settings.value = settings.value.copy(jamSpeakerDelayMs = -80)
        runCurrent()
        assertEquals(-80, delay())
    }

    @Test fun outOfRangeValuesAreClamped() = runTest {
        val settings = MutableStateFlow(AppSettings(jamSpeakerDelayMs = 900))
        val delay = jamSpeakerDelay(settings, backgroundScope)
        runCurrent()
        assertEquals(500, delay())
    }

    @Test fun theControllerReadsTheCurrentValue() = runTest {
        val settings = MutableStateFlow(AppSettings())
        val delay = jamSpeakerDelay(settings, backgroundScope)
        val env = JamEnvironment(
            hostTransport = { error("unused") }, connector = FakeConnector(), library = { emptyList() },
            nowMs = { 0 }, speakerDelayMs = delay, newCode = { "123456" },
        )
        settings.value = AppSettings(jamSpeakerDelayMs = 250)
        runCurrent()
        assertEquals(250, env.speakerDelayMs())
    }
}
