package de.yurtemre.sonora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Sleeve palette (DESIGN §1). Flat colours only. */
object SleeveColors {
    val Fog = Color(0xFFE9EBEF)
    val Ink = Color(0xFF141A2E)
    val Cobalt = Color(0xFF2A44D4)
    val Label = Color(0xFFF2C230)
    val Moss = Color(0xFF4F7A5A)
    val Night = Color(0xFF10142A)

    val AmoledBackground = Color(0xFF000000)
    val AmoledContainerLow = Color(0xFF0B0D18)
    val AmoledContainer = Color(0xFF11142A)
    val AmoledContainerHigh = Color(0xFF181C36)
}

/** Color source setting (mirrors Flutter "Color source"). Shared storage name `afterglow` maps to [Sleeve]. */
enum class ThemeColorSourceUi { Sleeve, System, AlbumArt, Custom }

/** Theme mode setting. */
enum class ThemeModeUi { System, Light, Dark }

/** Hand-tuned Sleeve light roles (DESIGN §1). */
val SleeveLightScheme: ColorScheme = lightColorScheme(
    primary = SleeveColors.Cobalt,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2FF),
    onPrimaryContainer = Color(0xFF0B1A6B),
    inversePrimary = Color(0xFF9DB0FF),
    secondary = SleeveColors.Label,
    onSecondary = SleeveColors.Ink,
    secondaryContainer = SleeveColors.Label,
    onSecondaryContainer = SleeveColors.Ink,
    tertiary = SleeveColors.Moss,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFE6D4),
    onTertiaryContainer = Color(0xFF12301B),
    background = SleeveColors.Fog,
    onBackground = SleeveColors.Ink,
    surface = SleeveColors.Fog,
    onSurface = SleeveColors.Ink,
    surfaceVariant = Color(0xFFE1E4EA),
    onSurfaceVariant = Color(0xFF454C63),
    surfaceTint = SleeveColors.Cobalt,
    inverseSurface = SleeveColors.Ink,
    inverseOnSurface = SleeveColors.Fog,
    error = Color(0xFFB3261E),
    onError = Color.White,
    outline = Color(0xFF767D93),
    outlineVariant = Color(0xFFC3C8D4),
    surfaceBright = Color(0xFFF3F4F7),
    surfaceDim = Color(0xFFD6DAE2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F4F7),
    surfaceContainer = Color(0xFFE1E4EA),
    surfaceContainerHigh = Color(0xFFD6DAE2),
    surfaceContainerHighest = Color(0xFFCBD0DA),
)

/** Hand-tuned Sleeve dark roles (DESIGN §1). */
val SleeveDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9DB0FF),
    onPrimary = Color(0xFF0B1A6B),
    primaryContainer = Color(0xFF2234A8),
    onPrimaryContainer = Color(0xFFDDE2FF),
    inversePrimary = SleeveColors.Cobalt,
    secondary = SleeveColors.Label,
    onSecondary = SleeveColors.Ink,
    secondaryContainer = SleeveColors.Label,
    onSecondaryContainer = SleeveColors.Ink,
    tertiary = Color(0xFF9CC7A6),
    onTertiary = Color(0xFF12301B),
    tertiaryContainer = Color(0xFF2E5638),
    onTertiaryContainer = Color(0xFFCFE6D4),
    background = SleeveColors.Night,
    onBackground = Color(0xFFE4E7F2),
    surface = SleeveColors.Night,
    onSurface = Color(0xFFE4E7F2),
    surfaceVariant = Color(0xFF1D2341),
    onSurfaceVariant = Color(0xFFA9AFC6),
    surfaceTint = Color(0xFF9DB0FF),
    inverseSurface = Color(0xFFE4E7F2),
    inverseOnSurface = SleeveColors.Ink,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF7C839C),
    outlineVariant = Color(0xFF363D5C),
    surfaceBright = Color(0xFF252C4D),
    surfaceDim = SleeveColors.Night,
    surfaceContainerLowest = Color(0xFF0B0E20),
    surfaceContainerLow = Color(0xFF171C36),
    surfaceContainer = Color(0xFF1D2341),
    surfaceContainerHigh = Color(0xFF252C4D),
    surfaceContainerHighest = Color(0xFF2D3558),
)

/** AMOLED ("Pitch black") surface overrides (DESIGN §1): navy-tinted containers. */
internal fun ColorScheme.withAmoledSurfaces(): ColorScheme = copy(
    background = SleeveColors.AmoledBackground,
    surface = SleeveColors.AmoledBackground,
    surfaceDim = SleeveColors.AmoledBackground,
    surfaceContainerLowest = SleeveColors.AmoledBackground,
    surfaceContainerLow = SleeveColors.AmoledContainerLow,
    surfaceContainer = SleeveColors.AmoledContainer,
    surfaceContainerHigh = SleeveColors.AmoledContainerHigh,
    surfaceContainerHighest = SleeveColors.AmoledContainerHigh,
    surfaceBright = SleeveColors.AmoledContainerHigh,
)
