package de.yurtemre.sonora.ui.screens.settings.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.ui.screens.jam.SPEAKER_DELAY_STEP_MS
import de.yurtemre.sonora.ui.screens.jam.signedMs
import de.yurtemre.sonora.ui.screens.jam.snapSpeakerDelay
import de.yurtemre.sonora.ui.screens.jam.stepSpeakerDelay
import de.yurtemre.sonora.ui.screens.settings.SettingsRow

private val Range = AppSettings.JAM_SPEAKER_DELAY_RANGE_MS

/**
 * Jam speaker delay (−500..+500 ms, step 10, default 0; per device, not backed up): a slider with − / + steppers.
 * The slider moves locally while dragged and saves once on release; the steppers save at once.
 */
@Composable
internal fun SpeakerDelayRow(valueMs: Int, onChange: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: valueMs
    val label = stringResource(R.string.settings_speaker_delay_value, signedMs(shown))
    Column {
        SettingsRow(
            stringResource(R.string.settings_speaker_delay),
            icon = Icons.Rounded.Speaker,
            subtitle = stringResource(R.string.settings_speaker_delay_subtitle),
            trailing = { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onChange(stepSpeakerDelay(valueMs, -1)) }, enabled = valueMs > Range.first) {
                Icon(Icons.Rounded.Remove, stringResource(R.string.settings_speaker_delay_less), Modifier.size(24.dp))
            }
            Slider(
                value = shown.toFloat(),
                onValueChange = {
                    val snapped = snapSpeakerDelay(it)
                    if (snapped != shown) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    dragging = snapped
                },
                onValueChangeFinished = {
                    dragging?.let(onChange)
                    dragging = null
                },
                valueRange = Range.first.toFloat()..Range.last.toFloat(),
                steps = (Range.last - Range.first) / SPEAKER_DELAY_STEP_MS - 1,
                modifier = Modifier.weight(1f).semantics { stateDescription = label },
            )
            IconButton(onClick = { onChange(stepSpeakerDelay(valueMs, +1)) }, enabled = valueMs < Range.last) {
                Icon(Icons.Rounded.Add, stringResource(R.string.settings_speaker_delay_more), Modifier.size(24.dp))
            }
        }
    }
}
