package de.yurtemre.sonora.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Corner scale (DESIGN §3). */
val AfterglowShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Squircle-ish artwork shape; never square or circle. */
val ArtworkShape = RoundedCornerShape(percent = 22)

/** Pills for buttons and chips. */
val PillShape = CircleShape
