package de.yurtemre.sonora.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A11Y-06: true when the user disabled animations (animator or transition scale == 0). */
fun isReduceMotion(context: Context): Boolean {
    val cr = context.contentResolver
    val animator = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val transition = Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
    return animator == 0f || transition == 0f
}

/** Observes the system animation scales so toggling the setting applies live. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduce by remember { mutableStateOf(isReduceMotion(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduce = isReduceMotion(context) }
        }
        val cr = context.contentResolver
        cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer)
        reduce = isReduceMotion(context)
        onDispose { cr.unregisterContentObserver(observer) }
    }
    return reduce
}

/** Motion scheme whose specs all snap (A11Y-06): springs and fades complete immediately. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object ReducedMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

/** WCAG 2.x contrast (pure; A11Y-07). */
object ContrastMath {
    private fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    fun luminance(argb: Color): Double =
        0.2126 * channel(argb.red) + 0.7152 * channel(argb.green) + 0.0722 * channel(argb.blue)

    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    const val MIN_TEXT = 4.5

    /** True when key text pairs reach [MIN_TEXT] (onPrimary/primary, onSurface/surface). */
    fun passes(s: ColorScheme): Boolean =
        ratio(s.onPrimary, s.primary) >= MIN_TEXT && ratio(s.onSurface, s.surface) >= MIN_TEXT
}
