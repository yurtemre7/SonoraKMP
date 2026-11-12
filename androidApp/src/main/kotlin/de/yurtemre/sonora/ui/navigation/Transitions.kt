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
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Predictive back: the outgoing scene also rounds to 32 dp corners while dragged ([predictiveBackCorners]). */
    fun <S> AnimatedContentTransitionScope<S>.predictivePop(): ContentTransform =
        fadeIn(tween(DURATION)) togetherWith (scaleOut(targetScale = 0.92f) + fadeOut())

    /** Fade-through for tab switches. */
    fun <S> AnimatedContentTransitionScope<S>.fadeThrough(): ContentTransform =
        (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.92f)) togetherWith
            fadeOut(tween(90))
}

private val PredictiveCorner = 32.dp

/**
 * Entry decorator that clips the outgoing scene to [PredictiveCorner] corners while a predictive back gesture seeks
 * the transition (DESIGN §4). Normal push/pop exits are left square.
 */
internal fun <T : Any> predictiveBackCorners(): NavEntryDecorator<T> = NavEntryDecorator { entry ->
    val transition = LocalNavAnimatedContentScope.current.transition
    val corner by transition.animateDp(label = "predictiveCorner") { if (it == EnterExitState.PostExit) PredictiveCorner else 0.dp }
    // A back gesture in progress (public API; Transition.isSeeking is library-restricted) on the outgoing entry.
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val idle = remember { MutableStateFlow<NavigationEventTransitionState>(NavigationEventTransitionState.Idle) }
    val gesture by (dispatcher?.transitionState ?: idle).collectAsState()
    val seeking = gesture is NavigationEventTransitionState.InProgress && transition.targetState == EnterExitState.PostExit
    Box(
        Modifier.graphicsLayer {
            if (seeking) {
                shape = RoundedCornerShape(corner)
                clip = true
            }
        },
    ) { entry.Content() }
}
