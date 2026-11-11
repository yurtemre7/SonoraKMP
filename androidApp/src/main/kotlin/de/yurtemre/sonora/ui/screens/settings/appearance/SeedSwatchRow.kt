package de.yurtemre.sonora.ui.screens.settings.appearance

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamicColorScheme
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.LocalSleeveExtras

/**
 * Accent swatches (SET-02): 56 dp circles painted as a 3-slice pie of the seed's primary / secondary / tertiary.
 * [swatches] are ARGB ints, already bucketed by `SeedColorBuckets.uniqueSwatches`.
 */
@Composable
fun SeedSwatchRow(swatches: List<Int>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(swatches, key = { _, c -> c }) { i, color ->
            val description = stringResource(R.string.settings_swatch_description, i + 1, swatches.size)
            SeedSwatch(
                seed = color,
                selected = color == selected,
                modifier = Modifier.semantics { contentDescription = description },
                onClick = {
                    if (color != selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(color)
                    }
                },
            )
        }
    }
}

@Composable
private fun SeedSwatch(seed: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = LocalSleeveExtras.current.isDark
    val slices = remember(seed, dark) {
        val s = dynamicColorScheme(seedColor = Color(seed), isDark = dark)
        listOf(s.primary, s.secondary, s.tertiary)
    }
    val cs = MaterialTheme.colorScheme
    val ring by animateDpAsState(if (selected) 3.dp else 1.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "ring")
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .border(ring, if (selected) cs.primary else cs.outlineVariant, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().clip(CircleShape)) {
            slices.forEachIndexed { i, c ->
                drawArc(c, startAngle = -90f + 120f * i, sweepAngle = 120f, useCenter = true)
            }
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}
