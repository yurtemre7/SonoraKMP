package de.yurtemre.sonora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.yurtemre.sonora.R

// One family: Bricolage Grotesque (DESIGN §2). Variation settings apply on API 26+.
private fun bricolage(weight: Int, width: Float, opsz: Float) = Font(
    R.font.bricolage_grotesque,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
        FontVariation.Setting("opsz", opsz.coerceIn(12f, 96f)),
    ),
)

/** Builds a Bricolage style; `opsz` follows the font size. */
fun sleeveStyle(size: Int, line: Int, weight: Int, width: Float = 100f) = TextStyle(
    fontFamily = FontFamily(bricolage(weight, width, size.toFloat())),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
)

/** Tabular figures for times, durations and counts. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** Sentence-case section header (titleMedium, DESIGN §6.6). */
val SectionHeaderTextStyle: TextStyle = sleeveStyle(16, 22, 600)

/** Emphasized variant: +100 weight, narrower. */
private fun emph(size: Int, line: Int, weight: Int, width: Float) =
    sleeveStyle(size, line, (weight + 100).coerceAtMost(900), (width - 10f).coerceAtLeast(75f))

val SleeveTypography: Typography = Typography(
    displayLarge = sleeveStyle(64, 64, 800, 75f),
    displayMedium = sleeveStyle(52, 56, 775, 75f),
    displaySmall = sleeveStyle(40, 44, 750, 75f),
    headlineLarge = sleeveStyle(32, 38, 700, 80f),
    headlineMedium = sleeveStyle(26, 32, 700, 80f),
    headlineSmall = sleeveStyle(22, 28, 700, 85f),
    titleLarge = sleeveStyle(20, 26, 650, 90f),
    titleMedium = sleeveStyle(16, 22, 600),
    titleSmall = sleeveStyle(14, 20, 600),
    bodyLarge = sleeveStyle(16, 24, 400),
    bodyMedium = sleeveStyle(14, 20, 400),
    bodySmall = sleeveStyle(12, 16, 400),
    labelLarge = sleeveStyle(14, 20, 600),
    labelMedium = sleeveStyle(12, 16, 600),
    labelSmall = sleeveStyle(11, 16, 600),
    displayLargeEmphasized = emph(64, 64, 800, 75f),
    displayMediumEmphasized = emph(52, 56, 775, 75f),
    displaySmallEmphasized = emph(40, 44, 750, 75f),
    headlineLargeEmphasized = emph(32, 38, 700, 80f),
    headlineMediumEmphasized = emph(26, 32, 700, 80f),
    headlineSmallEmphasized = emph(22, 28, 700, 85f),
    titleLargeEmphasized = emph(20, 26, 650, 90f),
    titleMediumEmphasized = emph(16, 22, 600, 100f),
    titleSmallEmphasized = emph(14, 20, 600, 100f),
    bodyLargeEmphasized = emph(16, 24, 400, 100f),
    bodyMediumEmphasized = emph(14, 20, 400, 100f),
    bodySmallEmphasized = emph(12, 16, 400, 100f),
    labelLargeEmphasized = emph(14, 20, 600, 100f),
    labelMediumEmphasized = emph(12, 16, 600, 100f),
    labelSmallEmphasized = emph(11, 16, 600, 100f),
)
