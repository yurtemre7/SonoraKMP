package de.yurtemre.sonora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Afterglow brand keys (DESIGN §1). */
object AfterglowColors {
    val Ember = Color(0xFFE5573B)
    val Dusk = Color(0xFF7A6BD6)
    val Seaglass = Color(0xFF2FA593)
    val Ink = Color(0xFF17131A)
    val Paper = Color(0xFFFBF6F1)

    val AmoledBackground = Color(0xFF000000)
    val AmoledContainerLow = Color(0xFF0A0A0A)
    val AmoledContainerHigh = Color(0xFF121212)
}

/** Color source setting (mirrors Flutter "Color source"); map from shared settings later. */
enum class ThemeColorSourceUi { Afterglow, System, AlbumArt, Custom }

/** Theme mode setting. */
enum class ThemeModeUi { System, Light, Dark }

/** Pins the hand-tuned brand roles from DESIGN §1 on top of the generated Afterglow scheme. */
internal fun ColorScheme.withAfterglowRoles(dark: Boolean): ColorScheme = if (dark) {
    copy(
        background = AfterglowColors.Ink,
        surface = AfterglowColors.Ink,
        surfaceContainerLow = Color(0xFF201B23),
        surfaceContainerHigh = Color(0xFF2C2630),
        primary = Color(0xFFFFB4A1),
        onPrimary = Color(0xFF5F1404),
        primaryContainer = Color(0xFF86240F),
        secondary = Color(0xFFC8BFFF),
        tertiary = Color(0xFF5ADBC4),
        outline = Color(0xFFA08D88),
    )
} else {
    copy(
        background = AfterglowColors.Paper,
        surface = AfterglowColors.Paper,
        surfaceContainerLow = Color(0xFFF5EDE6),
        surfaceContainerHigh = Color(0xFFECE1D8),
        primary = Color(0xFFA83A22),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDAD1),
        secondary = Color(0xFF5C4DB5),
        tertiary = Color(0xFF006B5E),
        outline = Color(0xFF85736E),
    )
}

/** AMOLED ("Pitch black") surface overrides (DESIGN §1). */
internal fun ColorScheme.withAmoledSurfaces(): ColorScheme = copy(
    background = AfterglowColors.AmoledBackground,
    surface = AfterglowColors.AmoledBackground,
    surfaceDim = AfterglowColors.AmoledBackground,
    surfaceContainerLowest = AfterglowColors.AmoledBackground,
    surfaceContainerLow = AfterglowColors.AmoledContainerLow,
    surfaceContainer = AfterglowColors.AmoledContainerLow,
    surfaceContainerHigh = AfterglowColors.AmoledContainerHigh,
    surfaceContainerHighest = AfterglowColors.AmoledContainerHigh,
    surfaceBright = AfterglowColors.AmoledContainerHigh,
)
