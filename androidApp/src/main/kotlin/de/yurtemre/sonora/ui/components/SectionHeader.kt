package de.yurtemre.sonora.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.theme.LocalSleeveExtras
import de.yurtemre.sonora.ui.theme.SonoraTheme
import de.yurtemre.sonora.ui.theme.tabular

/** Sentence-case section header (DESIGN §6.6) with an optional right-aligned count and trailing slot. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = LocalSleeveExtras.current.sectionHeader, color = cs.onSurface, modifier = Modifier.weight(1f))
        if (count != null) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium.tabular(), color = cs.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Preview
@Composable
private fun SectionHeaderPreview() = SonoraTheme {
    SectionHeader(androidx.compose.ui.res.stringResource(R.string.track_label), count = 42)
}
