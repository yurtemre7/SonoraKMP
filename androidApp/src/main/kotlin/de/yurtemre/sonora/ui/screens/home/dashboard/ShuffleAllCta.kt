package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.LocalReduceMotion

internal val HomeCtaShape = RoundedCornerShape(24.dp)

/** Prominent "Shuffle all" call to action: springs down while pressed, haptic tick on tap, inert when [enabled] is false. */
@Composable
fun ShuffleAllCta(enabled: Boolean, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduce) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "shuffleCtaScale",
    )
    val scheme = MaterialTheme.colorScheme
    val container = if (enabled) scheme.primary else scheme.surfaceContainerHigh
    val content = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.38f)
    val desc = stringResource(R.string.home_card_shuffle_all_cd)
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            onShuffle()
        },
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.scale(scale).semantics { contentDescription = desc },
        shape = HomeCtaShape,
        color = container,
        contentColor = content,
    ) {
        Column(
            Modifier.heightIn(min = 104.dp).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(Icons.Rounded.Shuffle, null, Modifier.size(32.dp))
            Text(
                stringResource(R.string.home_card_shuffle_all),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
