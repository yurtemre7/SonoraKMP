package de.yurtemre.sonora.ui.theme

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.animateColorScheme
import com.materialkolor.rememberDynamicMaterialThemeState

/** M3 emphasized easing used for scheme transitions. */
val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
const val ColorTransitionMillis = 450

/** Extra Afterglow tokens not covered by MaterialTheme. */
@Immutable
data class AfterglowExtras(
    val eyebrow: TextStyle = EyebrowTextStyle,
    val isDark: Boolean = false,
    val isAmoled: Boolean = false,
)

val LocalAfterglowExtras = staticCompositionLocalOf { AfterglowExtras() }

/** Placeholder; P6-04 wires this to ANIMATOR_DURATION_SCALE. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun SonoraTheme(
    colorSource: ThemeColorSourceUi = ThemeColorSourceUi.Afterglow,
    themeMode: ThemeModeUi = ThemeModeUi.System,
    amoled: Boolean = false,
    seedColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeModeUi.System -> isSystemInDarkTheme()
        ThemeModeUi.Light -> false
        ThemeModeUi.Dark -> true
    }
    val pitchBlack = amoled && dark

    val scheme = rememberSchemeFor(colorSource, dark, pitchBlack, seedColor)
    val animated = animateColorScheme(
        colorScheme = scheme,
        animationSpec = { tween(ColorTransitionMillis, easing = EmphasizedEasing) },
    )

    CompositionLocalProvider(
        LocalAfterglowExtras provides AfterglowExtras(isDark = dark, isAmoled = pitchBlack),
    ) {
        MaterialExpressiveTheme(
            colorScheme = animated,
            motionScheme = MotionScheme.expressive(),
            typography = AfterglowTypography,
            shapes = AfterglowShapes,
            content = content,
        )
    }
}

@Composable
private fun rememberSchemeFor(
    source: ThemeColorSourceUi,
    dark: Boolean,
    amoled: Boolean,
    seed: Color?,
): ColorScheme {
    if (source == ThemeColorSourceUi.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        val s = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        return if (amoled) s.withAmoledSurfaces() else s
    }
    val brand = source == ThemeColorSourceUi.Afterglow ||
        source == ThemeColorSourceUi.System || seed == null
    val state = if (brand) {
        rememberDynamicMaterialThemeState(
            seedColor = AfterglowColors.Ember,
            isDark = dark,
            isAmoled = amoled,
            primary = AfterglowColors.Ember,
            secondary = AfterglowColors.Dusk,
            tertiary = AfterglowColors.Seaglass,
            neutral = AfterglowColors.Ink,
            style = PaletteStyle.Expressive,
            contrastLevel = Contrast.Default.value,
        )
    } else {
        rememberDynamicMaterialThemeState(
            seedColor = seed!!,
            isDark = dark,
            isAmoled = amoled,
            style = PaletteStyle.Expressive,
            contrastLevel = Contrast.Default.value,
        )
    }
    var s = state.colorScheme
    if (brand) s = s.withAfterglowRoles(dark)
    return if (amoled) s.withAmoledSurfaces() else s
}

@Preview(name = "Afterglow light")
@Preview(name = "Afterglow dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SonoraThemePreview() {
    SonoraTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("UP NEXT", style = LocalAfterglowExtras.current.eyebrow)
                Text("Afterglow", style = MaterialTheme.typography.displaySmall)
                Text("Late-night listening room", style = MaterialTheme.typography.titleMedium)
                Text("Warm ember light on deep ink.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {}) { Text("Play") }
            }
        }
    }
}
