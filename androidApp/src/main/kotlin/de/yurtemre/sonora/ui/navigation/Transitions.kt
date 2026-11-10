package de.yurtemre.sonora.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import de.yurtemre.sonora.ui.theme.EmphasizedEasing

/** DESIGN §4 transitions: shared-axis X push/pop, predictive back scales the outgoing scene to 0.92. */
internal object SonoraTransitions {
    private const val DURATION = 400
    private const val SHIFT = 0.15f

    fun <S> AnimatedContentTransitionScope<S>.push(): ContentTransform =
        (slideInHorizontally(tween(DURATION, easing = EmphasizedEasing)) { (it * SHIFT).toInt() } +
            fadeIn(tween(DURATION / 2, delayMillis = DURATION / 4))) togetherWith
            (slideOutHorizontally(tween(DURATION, easing = EmphasizedEasing)) { -(it * SHIFT).toInt() } +
                fadeOut(tween(DURATION / 4)))

    fun <S> AnimatedContentTransitionScope<S>.pop(): ContentTransform =
        (slideInHorizontally(tween(DURATION, easing = EmphasizedEasing)) { -(it * SHIFT).toInt() } +
            fadeIn(tween(DURATION / 2, delayMillis = DURATION / 4))) togetherWith
            (slideOutHorizontally(tween(DURATION, easing = EmphasizedEasing)) { (it * SHIFT).toInt() } +
                fadeOut(tween(DURATION / 4)))

    // TODO(P6): corner radius 32 on the outgoing scene while dragging.
    fun <S> AnimatedContentTransitionScope<S>.predictivePop(): ContentTransform =
        fadeIn(tween(DURATION)) togetherWith (scaleOut(targetScale = 0.92f) + fadeOut())

    /** Fade-through for tab switches. */
    fun <S> AnimatedContentTransitionScope<S>.fadeThrough(): ContentTransform =
        (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.92f)) togetherWith
            fadeOut(tween(90))
}
