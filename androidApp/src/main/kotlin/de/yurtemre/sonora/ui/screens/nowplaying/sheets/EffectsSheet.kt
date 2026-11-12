package de.yurtemre.sonora.ui.screens.nowplaying.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.MfxUiState
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.model.EqMode
import de.yurtemre.sonora.shared.model.EqPreset
import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.ui.format.currentLocale
import org.koin.compose.koinInject

/** Effects sheet (UI-20, PLAY-12..16): presets, EQ controls, and custom speed/pitch at the bottom. */
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
    val hasActiveEffects = !mfx.preset.isNormal ||
        mfx.customSpeed != 1f ||
        mfx.customPitch != 1f ||
        mfx.activeEqMode != EqMode.Off

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(
                canReset = hasActiveEffects,
                onReset = {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    connection.resetAllEffects()
                },
            )

            PresetsSection(
                mfx = mfx,
                onSelectPreset = { p ->
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    mfx = mfx.copy(preset = p)
                    connection.setMfxPreset(p)
                },
            )

            if (mfx.isEqAvailable) {
                EqualizerSection(
                    mfx = mfx,
                    onEqToggle = { bass, warmth, lofi ->
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        // PLAY-14: bass/warmth/lo-fi exclude each other; custom EQ is unaffected.
                        when {
                            bass != mfx.bassBoost -> {
                                mfx = mfx.copy(bassBoost = bass, warmth = false, loFi = false)
                                connection.setBassBoost(bass)
                            }
                            warmth != mfx.warmth -> {
                                mfx = mfx.copy(warmth = warmth, bassBoost = false, loFi = false)
                                connection.setWarmth(warmth)
                            }
                            lofi != mfx.loFi -> {
                                mfx = mfx.copy(loFi = lofi, bassBoost = false, warmth = false)
                                connection.setLoFi(lofi)
                            }
                        }
                    },
                    onCustomEqToggle = { enabled ->
                        mfx = mfx.copy(customEqEnabled = enabled)
                        connection.setCustomEqEnabled(enabled)
                    },
                    onEqPreset = { preset ->
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        connection.applyEqPreset(preset)
                    },
                    onBandChange = { band, gain ->
                        mfx = mfx.copy(
                            customEqEnabled = true,
                            customEqGains = mfx.customEqGains.toMutableList().also { it[band] = gain },
                        )
                        connection.setCustomEqBandGain(band, gain)
                    },
                )
            }

            SpeedAndPitchSection(
                mfx = mfx,
                onSpeedCommit = { s ->
                    mfx = mfx.copy(customSpeed = s)
                    connection.setCustomSpeed(s)
                },
                onPitchCommit = { p ->
                    mfx = mfx.copy(customPitch = p)
                    connection.setCustomPitch(p)
                },
            )
        }
    }
}

@Composable
private fun SheetHeader(canReset: Boolean, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.effects_sheet_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onReset,
            enabled = canReset,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.effects_reset_short))
        }
    }
}

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun ValueBadge(text: String, highlighted: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) cs.primaryContainer else cs.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) cs.onPrimaryContainer else cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsSection(mfx: MfxUiState, onSelectPreset: (MfxPreset) -> Unit) {
    SectionCard(
        icon = Icons.Rounded.AutoAwesome,
        title = stringResource(R.string.effects_speed_presets),
        trailing = {
            ValueBadge(
                text = stringResource(R.string.effects_speed_pitch_value, "%.2f".format(currentLocale(), mfx.effectiveSpeed)),
                highlighted = !mfx.preset.isNormal,
            )
        },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MfxPreset.entries.forEach { p ->
                val selected = mfx.preset == p
                FilterChip(
                    selected = selected,
                    onClick = { onSelectPreset(p) },
                    label = { Text(stringResource(presetLabel(p))) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerSection(
    mfx: MfxUiState,
    onEqToggle: (bass: Boolean, warmth: Boolean, lofi: Boolean) -> Unit,
    onCustomEqToggle: (Boolean) -> Unit,
    onEqPreset: (EqPreset) -> Unit,
    onBandChange: (Int, Float) -> Unit,
) {
    SectionCard(
        icon = Icons.Rounded.Equalizer,
        title = stringResource(R.string.effects_eq),
    ) {
        EqToggles(mfx, onEqToggle)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.effects_custom_eq),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = mfx.customEqEnabled, onCheckedChange = onCustomEqToggle)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EqPreset.entries.forEach { p ->
                SuggestionChip(
                    onClick = { onEqPreset(p) },
                    label = { Text(stringResource(eqPresetLabel(p))) },
                )
            }
        }

        EqBands(mfx, onBandChange)
    }
}

@Composable
private fun SpeedAndPitchSection(
    mfx: MfxUiState,
    onSpeedCommit: (Float) -> Unit,
    onPitchCommit: (Float) -> Unit,
) {
    val enabled = mfx.isCustomSpeedEnabled
    SectionCard(
        icon = Icons.Rounded.Tune,
        title = stringResource(R.string.effects_speed_pitch_section),
    ) {
        if (!enabled) {
            Text(
                text = stringResource(R.string.effects_speed_pitch_preset_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RateSlider(
            icon = Icons.Rounded.Speed,
            label = stringResource(R.string.effects_custom_speed_label),
            value = mfx.effectiveSpeed,
            enabled = enabled,
            onCommit = onSpeedCommit,
        )
        RateSlider(
            icon = Icons.Rounded.GraphicEq,
            label = stringResource(R.string.effects_custom_pitch_label),
            value = mfx.effectivePitch,
            enabled = enabled,
            onCommit = onPitchCommit,
        )
    }
}

@Composable
private fun RateSlider(
    icon: ImageVector,
    label: String,
    value: Float,
    enabled: Boolean,
    onCommit: (Float) -> Unit,
) {
    var drag by remember(value) { mutableFloatStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ValueBadge(
                text = stringResource(R.string.effects_speed_pitch_value, "%.2f".format(currentLocale(), drag)),
                highlighted = enabled && drag != 1f,
            )
        }
        Slider(
            value = drag,
            onValueChange = { drag = Math.round(it * 20f) / 20f },
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
            checked = mfx.bassBoost,
            onCheckedChange = { onChange(it, mfx.warmth, mfx.loFi) },
            modifier = Modifier.weight(1f).height(48.dp),
            shapes = connectedShapes(0, 3),
        ) { Text(stringResource(R.string.effects_bass_boost), maxLines = 1) }
        ToggleButton(
            checked = mfx.warmth,
            onCheckedChange = { onChange(mfx.bassBoost, it, mfx.loFi) },
            modifier = Modifier.weight(1f).height(48.dp),
            shapes = connectedShapes(1, 3),
        ) { Text(stringResource(R.string.effects_warmth), maxLines = 1) }
        ToggleButton(
            checked = mfx.loFi,
            onCheckedChange = { onChange(mfx.bassBoost, mfx.warmth, it) },
            modifier = Modifier.weight(1f).height(48.dp),
            shapes = connectedShapes(2, 3),
        ) { Text(stringResource(R.string.effects_lofi), maxLines = 1) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EqBands(mfx: MfxUiState, onBand: (Int, Float) -> Unit) {
    val minDb = mfx.eqMinLevelMb / 100f
    val maxDb = mfx.eqMaxLevelMb / 100f
    if (maxDb <= minDb || mfx.eqBandCenterHz.isEmpty()) return
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            mfx.eqBandCenterHz.forEachIndexed { band, hz ->
                val gain = mfx.customEqGains.getOrNull(band) ?: 0f
                val label = bandLabel(hz)
                val state = remember(band, minDb, maxDb) { SliderState(gain, 0, minDb..maxDb) }
                LaunchedEffect(gain) { if (!state.isDragging) state.value = gain }
                val formattedGain = "%.1f".format(currentLocale(), state.value)
                val desc = stringResource(R.string.effects_band_desc, label, formattedGain)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = formattedGain,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VerticalSlider(
                        state = state,
                        onValueChange = { state.value = it },
                        onValueChangeFinished = { onBand(band, state.value) },
                        modifier = Modifier.height(160.dp).width(44.dp).semantics { contentDescription = desc },
                        topToBottom = false, // min at the bottom
                    )
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun bandLabel(hz: Int): String =
    if (hz >= 1000) stringResource(R.string.effects_band_khz, "%.1f".format(currentLocale(), hz / 1000f).removeSuffix(".0").removeSuffix(",0"))
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
