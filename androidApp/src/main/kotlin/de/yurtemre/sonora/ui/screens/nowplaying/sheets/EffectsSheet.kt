package de.yurtemre.sonora.ui.screens.nowplaying.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.MfxUiState
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.model.EqPreset
import de.yurtemre.sonora.shared.model.MfxPreset
import org.koin.compose.koinInject
import java.util.Locale

/** Effects sheet (UI-20, PLAY-12..16): speed/pitch presets, custom speed, EQ toggles, custom EQ, reset. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EffectsSheet(
    onDismiss: () -> Unit,
    connection: PlaybackConnection = koinInject(),
) {
    val remote by connection.mfx.collectAsState()
    // Optimistic copy: updated immediately on tap, reconciled whenever the player state changes.
    var mfx by remember { mutableStateOf(remote) }
    LaunchedEffect(remote) { mfx = remote }
    val haptics = LocalHapticFeedback.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.effects_sheet_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.effects_speed_presets), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MfxPreset.entries.forEach { p ->
                    val selected = mfx.preset == p
                    FilterChip(
                        selected = selected,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            mfx = mfx.copy(preset = p)
                            connection.setMfxPreset(p)
                        },
                        label = { Text(stringResource(presetLabel(p))) },
                        leadingIcon = if (selected) { { Icon(Icons.Filled.Check, null) } } else null,
                        modifier = Modifier.height(40.dp),
                    )
                }
            }
            CustomSpeed(mfx) { s -> mfx = mfx.copy(customSpeed = s) ; connection.setCustomSpeed(s) }

            if (mfx.isEqAvailable) {
                HorizontalDivider()
                Text(stringResource(R.string.effects_eq), style = MaterialTheme.typography.titleSmall)
                EqToggles(mfx) { bass, warmth, lofi ->
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    // PLAY-14: bass/warmth/lo-fi exclude each other; custom EQ is unaffected.
                    when {
                        bass != mfx.bassBoost -> { mfx = mfx.copy(bassBoost = bass, warmth = false, loFi = false); connection.setBassBoost(bass) }
                        warmth != mfx.warmth -> { mfx = mfx.copy(warmth = warmth, bassBoost = false, loFi = false); connection.setWarmth(warmth) }
                        lofi != mfx.loFi -> { mfx = mfx.copy(loFi = lofi, bassBoost = false, warmth = false); connection.setLoFi(lofi) }
                    }
                }
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.effects_custom_eq), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = mfx.customEqEnabled, onCheckedChange = {
                        mfx = mfx.copy(customEqEnabled = it); connection.setCustomEqEnabled(it)
                    })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EqPreset.entries.forEach { p ->
                        SuggestionChip(
                            onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); connection.applyEqPreset(p) },
                            label = { Text(stringResource(eqPresetLabel(p))) },
                        )
                    }
                }
                EqBands(mfx) { band, gain ->
                    mfx = mfx.copy(
                        customEqEnabled = true,
                        customEqGains = mfx.customEqGains.toMutableList().also { it[band] = gain },
                    )
                    connection.setCustomEqBandGain(band, gain)
                }
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.Reject); connection.resetAllEffects() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.effects_reset)) }
        }
    }
}

@Composable
private fun CustomSpeed(mfx: MfxUiState, onCommit: (Float) -> Unit) {
    var drag by remember(mfx.customSpeed) { mutableFloatStateOf(mfx.customSpeed) }
    val enabled = mfx.isCustomSpeedEnabled
    Column {
        Text(
            if (enabled) stringResource(R.string.effects_custom_speed, "%.2f".format(Locale.getDefault(), drag))
            else stringResource(R.string.effects_custom_speed_disabled),
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = drag,
            onValueChange = { drag = (Math.round(it * 20f) / 20f) },
            onValueChangeFinished = { onCommit(drag) },
            valueRange = 0.5f..2.0f,
            steps = 29,
            enabled = enabled,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EqToggles(mfx: MfxUiState, onChange: (bass: Boolean, warmth: Boolean, lofi: Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ToggleButton(
            checked = mfx.bassBoost, onCheckedChange = { onChange(it, mfx.warmth, mfx.loFi) },
            modifier = Modifier.weight(1f).height(56.dp), shapes = connectedShapes(0, 3),
        ) { Text(stringResource(R.string.effects_bass_boost), maxLines = 1) }
        ToggleButton(
            checked = mfx.warmth, onCheckedChange = { onChange(mfx.bassBoost, it, mfx.loFi) },
            modifier = Modifier.weight(1f).height(56.dp), shapes = connectedShapes(1, 3),
        ) { Text(stringResource(R.string.effects_warmth), maxLines = 1) }
        ToggleButton(
            checked = mfx.loFi, onCheckedChange = { onChange(mfx.bassBoost, mfx.warmth, it) },
            modifier = Modifier.weight(1f).height(56.dp), shapes = connectedShapes(2, 3),
        ) { Text(stringResource(R.string.effects_lofi), maxLines = 1) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EqBands(mfx: MfxUiState, onBand: (Int, Float) -> Unit) {
    val minDb = mfx.eqMinLevelMb / 100f
    val maxDb = mfx.eqMaxLevelMb / 100f
    if (maxDb <= minDb || mfx.eqBandCenterHz.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        mfx.eqBandCenterHz.forEachIndexed { band, hz ->
            val gain = mfx.customEqGains.getOrNull(band) ?: 0f
            val label = bandLabel(hz)
            val state = remember(band, minDb, maxDb) { SliderState(value = gain, valueRange = minDb..maxDb) }
            LaunchedEffect(gain) { if (!state.isDragging) state.value = gain }
            state.onValueChangeFinished = { onBand(band, state.value) }
            val desc = stringResource(R.string.effects_band_desc, label, "%.1f".format(Locale.getDefault(), state.value))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VerticalSlider(
                    state = state,
                    modifier = Modifier.height(180.dp).width(48.dp).semantics { contentDescription = desc },
                    reverseDirection = true,
                )
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun bandLabel(hz: Int): String =
    if (hz >= 1000) stringResource(R.string.effects_band_khz, "%.1f".format(Locale.getDefault(), hz / 1000f).removeSuffix(".0").removeSuffix(",0"))
    else stringResource(R.string.effects_band_hz, hz)

private fun presetLabel(p: MfxPreset): Int = when (p) {
    MfxPreset.SuperSlowed -> R.string.effects_preset_superSlowed
    MfxPreset.Slowed075 -> R.string.effects_preset_slowed075
    MfxPreset.Slowed080 -> R.string.effects_preset_slowed080
    MfxPreset.Slowed -> R.string.effects_preset_slowed
    MfxPreset.Slowed090 -> R.string.effects_preset_slowed090
    MfxPreset.Slowed095 -> R.string.effects_preset_slowed095
    MfxPreset.Normal -> R.string.effects_preset_normal
    MfxPreset.SpedUp105 -> R.string.effects_preset_spedUp105
    MfxPreset.SpedUp110 -> R.string.effects_preset_spedUp110
    MfxPreset.SpedUp115 -> R.string.effects_preset_spedUp115
    MfxPreset.SpedUp120 -> R.string.effects_preset_spedUp120
    MfxPreset.SpedUp -> R.string.effects_preset_spedUp
    MfxPreset.Nightcore -> R.string.effects_preset_nightcore
    MfxPreset.SuperSpedUp -> R.string.effects_preset_superSpedUp
}

private fun eqPresetLabel(p: EqPreset): Int = when (p) {
    EqPreset.Flat -> R.string.effects_eq_flat
    EqPreset.Pop -> R.string.effects_eq_pop
    EqPreset.Rock -> R.string.effects_eq_rock
    EqPreset.Jazz -> R.string.effects_eq_jazz
    EqPreset.Classical -> R.string.effects_eq_classical
}
