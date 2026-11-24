package de.yurtemre.sonora.ui.screens.jam

import de.yurtemre.sonora.shared.model.AppSettings
import kotlin.math.abs
import kotlin.math.roundToInt

// Pure helpers for the speaker delay row in Settings › Playback (unit-tested in JamPresentationTest).

/** Slider and stepper step, in ms. */
const val SPEAKER_DELAY_STEP_MS = 10

private val range = AppSettings.JAM_SPEAKER_DELAY_RANGE_MS

/** A slider value → the nearest multiple of [SPEAKER_DELAY_STEP_MS] inside −500..+500. */
fun snapSpeakerDelay(value: Float): Int =
    ((value / SPEAKER_DELAY_STEP_MS).roundToInt() * SPEAKER_DELAY_STEP_MS).coerceIn(range)

/** One stepper press ([direction] +1 or −1): the next multiple of [SPEAKER_DELAY_STEP_MS] that way, kept in range. */
fun stepSpeakerDelay(ms: Int, direction: Int): Int {
    val step = SPEAKER_DELAY_STEP_MS
    val down = Math.floorDiv(ms, step) * step
    val next = when {
        direction > 0 -> if (down == ms) ms + step else down + step
        direction < 0 -> if (down == ms) ms - step else down
        else -> ms
    }
    return next.coerceIn(range)
}

/** "0", "+120", "−80" (a real minus sign); the unit comes from the string resource. */
fun signedMs(ms: Int): String = when {
    ms > 0 -> "+$ms"
    ms < 0 -> "−${abs(ms)}"
    else -> "0"
}
