package de.yurtemre.sonora.ui.screens.settings.formatting

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GroupRemove
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.TitleFormatter
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import de.yurtemre.sonora.ui.screens.settings.SwitchRow
import de.yurtemre.sonora.ui.screens.settings.settingsHeader
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** SET-03: live preview of `TitleFormatter.displayTitle` + the two title filters. */
@Composable
fun SettingsFormattingRoute(onBack: () -> Unit) {
    val repo: SettingsRepository = koinInject()
    val settings by repo.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    val sampleTitle = stringResource(R.string.settings_formatting_sample_title)
    val sampleArtist = stringResource(R.string.settings_formatting_sample_artist)

    SettingsScaffold(stringResource(R.string.settings_formatting_title), onBack) {
        settingsHeader("preview") { stringResource(R.string.settings_formatting_preview) }
        item(key = "preview_card") {
            val shown = TitleFormatter.displayTitle(
                sampleTitle, sampleArtist, settings.filterTitleFeatures, settings.filterTitleArtist, settings.filterTitleVideoTags,
            )
            PreviewCard(sampleTitle, shown)
        }
        settingsHeader("filters") { stringResource(R.string.settings_section_title_filters) }
        item(key = "features") {
            SwitchRow(
                stringResource(R.string.settings_filter_features), settings.filterTitleFeatures,
                { v -> scope.launch { repo.setFilterTitleFeatures(v) } }, icon = Icons.Rounded.GroupRemove,
                subtitle = stringResource(R.string.settings_filter_features_subtitle),
            )
        }
        item(key = "artist") {
            SwitchRow(
                stringResource(R.string.settings_filter_artist), settings.filterTitleArtist,
                { v -> scope.launch { repo.setFilterTitleArtist(v) } }, icon = Icons.Rounded.PersonOff,
                subtitle = stringResource(R.string.settings_filter_artist_subtitle),
            )
        }
        item(key = "video_tags") {
            SwitchRow(
                stringResource(R.string.settings_filter_video_tags), settings.filterTitleVideoTags,
                { v -> scope.launch { repo.setFilterTitleVideoTags(v) } }, icon = Icons.Rounded.VideocamOff,
                subtitle = stringResource(R.string.settings_filter_video_tags_subtitle),
            )
        }
    }
}

@Composable
private fun PreviewCard(original: String, shown: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_formatting_original), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Text(
                original,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurfaceVariant,
                textDecoration = if (original != shown) TextDecoration.LineThrough else null,
            )
            Text(
                stringResource(R.string.settings_formatting_displayed),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            AnimatedContent(shown, label = "displayed") { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
