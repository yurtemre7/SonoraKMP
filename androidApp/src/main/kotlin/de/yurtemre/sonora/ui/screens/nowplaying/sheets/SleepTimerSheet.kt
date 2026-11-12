package de.yurtemre.sonora.ui.screens.nowplaying.sheets

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.playback.api.SleepTimerUiState
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.format.DurationFormat
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private val PresetMinutes = listOf(5, 15, 30, 45, 60)
private val FadeOptions = listOf(0, 5, 10, 30)

/** Sleep timer sheet (UI-19, PLAY-26/27): presets, custom duration, finish-song, fade, live countdown. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SleepTimerSheet(
    onDismiss: () -> Unit,
    connection: PlaybackConnection = koinInject(),
    settingsRepository: SettingsRepository = koinInject(),
) {
    val state by connection.sleepTimer.collectAsState()
    val haptics = LocalHapticFeedback.current
    var fade by rememberSaveable { mutableIntStateOf(10) }
    var finishSong by rememberSaveable { mutableStateOfBool(false) }
    var finishTouched by rememberSaveable { mutableStateOfBool(false) }
    // null = no duration (finish-song only), -1 = custom slider value, otherwise preset minutes.
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var customMin by rememberSaveable { mutableFloatStateOf(20f) }
    var defaultsApplied by rememberSaveable { mutableStateOfBool(false) }
    LaunchedEffect(Unit) {
        val d = settingsRepository.settings.first()
        if (!finishTouched) finishSong = d.sleepTimerFinishSong
        // Preselect sleep_timer_default_minutes once (a preset chip, else the custom slider).
        if (!defaultsApplied && selected == null) {
            val m = d.sleepTimerDefaultMinutes
            if (m in PresetMinutes) selected = m
            else if (m > 0) { customMin = m.coerceIn(1, 180).toFloat(); selected = -1 }
        }
        defaultsApplied = true
        fade = d.sleepTimerFadeOutSeconds.takeIf { it in FadeOptions } ?: fade
    }
    val minutes: Int? = when (val sel = selected) {
        null -> null
        -1 -> customMin.roundToInt()
        else -> sel
    }

    fun start() {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        connection.startSleepTimer((minutes ?: 0) * 60_000L, finishSong, fade)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.sleep_sheet_title), style = MaterialTheme.typography.headlineSmall)
            AnimatedContent(targetState = state is SleepTimerUiState.Inactive, label = "sleep") { inactive ->
                if (!inactive) RunningSection(state, connection) else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.sleep_sheet_presets), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        PresetMinutes.forEachIndexed { i, m ->
                            ToggleButton(
                                checked = selected == m,
                                onCheckedChange = {
                                    selected = if (it) m else null
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                shapes = connectedShapes(i, PresetMinutes.size),
                            ) { Text(stringResource(R.string.sleep_preset_minutes, m), maxLines = 1) }
                        }
                    }
                    Text(stringResource(R.string.sleep_custom_label, customMin.roundToInt()), style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = customMin,
                        onValueChange = {
                            if (it.roundToInt() != customMin.roundToInt()) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            customMin = it
                            selected = -1
                        },
                        valueRange = 1f..180f,
                    )
                    Row(
                        Modifier.fillMaxWidth().height(56.dp)
                            .toggleable(finishSong, role = Role.Switch) { finishSong = it; finishTouched = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.sleep_finish_song), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.sleep2_finish_song_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = finishSong, onCheckedChange = null)
                    }
                    Text(
                        if (fade == 0) stringResource(R.string.sleep_fade_off) else stringResource(R.string.sleep_fade_label, fade),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        FadeOptions.forEachIndexed { i, s ->
                            ToggleButton(
                                checked = fade == s,
                                onCheckedChange = { fade = s; haptics.performHapticFeedback(HapticFeedbackType.ToggleOn) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shapes = connectedShapes(i, FadeOptions.size),
                            ) { Text(if (s == 0) stringResource(R.string.sleep_fade_option_off) else stringResource(R.string.sleep_fade_option, s), maxLines = 1) }
                        }
                    }
                    Button(
                        onClick = { start(); onDismiss() },
                        enabled = minutes != null || finishSong,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text(
                            when {
                                minutes != null -> stringResource(R.string.sleep_custom_start, minutes)
                                finishSong -> stringResource(R.string.sleep2_start_finish_only)
                                else -> stringResource(R.string.sleep2_start)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RunningSection(state: SleepTimerUiState, connection: PlaybackConnection) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val fading = when (state) {
            is SleepTimerUiState.Countdown -> state.isFading
            is SleepTimerUiState.FinishSong -> state.isFading
            else -> false
        }
        when (state) {
            is SleepTimerUiState.Countdown -> {
                val progress = if (state.totalMs > 0) state.remainingMs.toFloat() / state.totalMs else 0f
                CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(160.dp))
                Text(stringResource(R.string.sleep_remaining), style = MaterialTheme.typography.labelLarge)
                Text(
                    DurationFormat.clockMs(state.remainingMs),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (state.finishSong) Text(stringResource(R.string.sleep2_then_finish), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is SleepTimerUiState.FinishSong -> Text(stringResource(R.string.sleep2_finishing_status), style = MaterialTheme.typography.titleLarge)
            SleepTimerUiState.Inactive -> Unit
        }
        if (fading) Text(stringResource(R.string.sleep_fading), color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state !is SleepTimerUiState.Inactive) {
                FilledTonalButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); connection.extendSleepTimer(60_000L) },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text(stringResource(R.string.sleep_extend)) }
            }
            OutlinedButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.Reject); connection.cancelSleepTimer() },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(stringResource(R.string.sleep_end)) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun connectedShapes(index: Int, count: Int) = when (index) {
    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}

private fun mutableStateOfBool(v: Boolean) = androidx.compose.runtime.mutableStateOf(v)
