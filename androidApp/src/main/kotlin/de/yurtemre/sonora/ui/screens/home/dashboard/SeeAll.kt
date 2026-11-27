package de.yurtemre.sonora.ui.screens.home.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.LocalSleeveExtras
import de.yurtemre.sonora.ui.theme.SleeveShape

/** Section title that opens the full list: the whole row is the tap target, the chevron says so. */
@Composable
fun LinkedSectionHeader(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val openLabel = stringResource(R.string.home_section_open_cd, title)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = openLabel, onClick = onClick)
            .padding(horizontal = 16.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = LocalSleeveExtras.current.sectionHeader, color = MaterialTheme.colorScheme.onSurface)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The last slot of a Home row: opens the full screen. Square, so it lines up with the cover tiles beside it. */
@Composable
fun SeeAllTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = SleeveShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.home_see_all), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Fixed-width See all card for the horizontal song carousels. */
@Composable
fun SeeAllCard(onClick: () -> Unit, modifier: Modifier = Modifier) = SeeAllTile(onClick, modifier.size(128.dp))
