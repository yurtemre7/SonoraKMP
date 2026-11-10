package de.yurtemre.sonora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.yurtemre.sonora.R

// Variable fonts: variation settings apply on API 26+; API 24/25 fall back to the default instance.
private fun bricolage(weight: Int, width: Float = 100f, opsz: Float) = Font(
    R.font.bricolage_grotesque,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
        FontVariation.Setting("opsz", opsz),
    ),
)

private fun figtree(weight: Int) = Font(
    R.font.figtree,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun bricolageStyle(size: Int, line: Int, weight: Int, width: Float = 100f) = TextStyle(
    fontFamily = FontFamily(bricolage(weight, width, size.toFloat())),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
)

private fun figtreeStyle(size: Int, line: Int, weight: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = FontFamily(figtree(weight)),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/** Emphasized variant: +100 weight, wdth 85 for Bricolage (DESIGN §2). */
private fun bricolageEmph(size: Int, line: Int, weight: Int) = bricolageStyle(size, line, weight + 100, 85f)
private fun figtreeEmph(size: Int, line: Int, weight: Int, tracking: Float = 0f) =
    figtreeStyle(size, line, weight + 100, tracking)

/** Section eyebrow ("UP NEXT"): labelSmall; callers uppercase the text. */
val EyebrowTextStyle: TextStyle = figtreeStyle(11, 16, 600, 0.6f)

val AfterglowTypography: Typography = Typography(
    displayLarge = bricolageStyle(57, 64, 800, 90f),
    displayMedium = bricolageStyle(45, 52, 750),
    displaySmall = bricolageStyle(36, 44, 700),
    headlineLarge = bricolageStyle(32, 40, 700),
    headlineMedium = bricolageStyle(28, 36, 700),
    headlineSmall = bricolageStyle(24, 32, 650),
    titleLarge = bricolageStyle(22, 28, 650),
    titleMedium = figtreeStyle(16, 24, 600),
    titleSmall = figtreeStyle(14, 20, 600),
    bodyLarge = figtreeStyle(16, 24, 400),
    bodyMedium = figtreeStyle(14, 20, 400),
    bodySmall = figtreeStyle(12, 16, 400),
    labelLarge = figtreeStyle(14, 20, 600),
    labelMedium = figtreeStyle(12, 16, 600),
    labelSmall = EyebrowTextStyle,
    displayLargeEmphasized = bricolageEmph(57, 64, 800),
    displayMediumEmphasized = bricolageEmph(45, 52, 750),
    displaySmallEmphasized = bricolageEmph(36, 44, 700),
    headlineLargeEmphasized = bricolageEmph(32, 40, 700),
    headlineMediumEmphasized = bricolageEmph(28, 36, 700),
    headlineSmallEmphasized = bricolageEmph(24, 32, 650),
    titleLargeEmphasized = bricolageEmph(22, 28, 650),
    titleMediumEmphasized = figtreeEmph(16, 24, 600),
    titleSmallEmphasized = figtreeEmph(14, 20, 600),
    bodyLargeEmphasized = figtreeEmph(16, 24, 400),
    bodyMediumEmphasized = figtreeEmph(14, 20, 400),
    bodySmallEmphasized = figtreeEmph(12, 16, 400),
    labelLargeEmphasized = figtreeEmph(14, 20, 600),
    labelMediumEmphasized = figtreeEmph(12, 16, 600),
    labelSmallEmphasized = figtreeEmph(11, 16, 600, 0.6f),
)
