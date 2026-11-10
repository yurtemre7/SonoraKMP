package de.yurtemre.sonora.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Corner scale 4 / 8 / 12 / 20 / 28 (DESIGN §3). */
val SleeveShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Artwork is a sleeve: square with 8 dp corners. */
val SleeveShape = RoundedCornerShape(8.dp)

/** Sleeve thumbnails in rows: 6 dp corners. */
val SleeveRowShape = RoundedCornerShape(6.dp)

/** Pills for buttons and chips only. */
val PillShape = CircleShape
