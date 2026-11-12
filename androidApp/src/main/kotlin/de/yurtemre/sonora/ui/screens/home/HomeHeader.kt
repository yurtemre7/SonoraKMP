package de.yurtemre.sonora.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.yurtemre.sonora.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.shared.repo.api.BackgroundWork
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.ui.theme.LocalReduceMotion
import org.koin.compose.koinInject
import de.yurtemre.sonora.ui.components.DevBadge
import de.yurtemre.sonora.ui.format.DurationFormat
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.labelRes
import androidx.compose.ui.semantics.contentDescription
import de.yurtemre.sonora.ui.theme.tabular
import java.time.LocalTime

/** Greeting resource for the hour (UI-04): <12 morning, <17 afternoon, else evening. */
internal fun greetingRes(hour: Int = LocalTime.now().hour): Int = when {
    hour < 12 -> R.string.good_morning
    hour < 17 -> R.string.good_afternoon
    else -> R.string.good_evening
}

/**
 * Home header (DESIGN §6b.3): condensed title (greeting only when a name is set) with count and total
 * time in tabular figures, then one connected button group Search · Sort · Shuffle. Search expands in
 * place into a field with a shared-axis (Y) transition.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeHeader(
    tab: LibraryTab,
    state: HomeTabState,
    userName: String?,
    isSyncing: Boolean,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSortClick: () -> Unit,
    onShuffle: () -> Unit,
    onRecentlyPlayed: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
) {
    BackHandler(enabled = searchOpen, onBack = onCloseSearch)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(de.yurtemre.sonora.ui.components.SonoraInsets.top)) {
        AnimatedContent(
            targetState = searchOpen,
            transitionSpec = {
                val dir = if (targetState) 1 else -1
                (fadeIn(tween(210, delayMillis = 60)) + slideInVertically(tween(300)) { dir * it / 3 }) togetherWith
                    (fadeOut(tween(90)) + slideOutVertically(tween(300)) { -dir * it / 3 }) using SizeTransform(clip = false)
            },
            label = "homeSearch",
        ) { open ->
            if (open) {
                Column {
                    SearchField(query, onQueryChange, onCloseSearch)
                    Counts(tab, state, Modifier.padding(start = 20.dp, bottom = 8.dp))
                }
            } else {
                Column(Modifier.padding(bottom = 12.dp)) {
                    TitleRow(userName, tab, state, onRecentlyPlayed, onFavorites, onSettings)
                    ActionGroup(onOpenSearch, onSortClick, onShuffle, tab)
                }
            }
        }
        val work by koinInject<LibraryRepository>().backgroundWork.collectAsStateWithLifecycle()
        BackgroundWorkProgress(work, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        if (isSyncing) LinearProgressIndicator(trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
    }
}

/** Stage label for a [BackgroundWork.Stage]. */
@Composable
internal fun stageLabel(stage: BackgroundWork.Stage): String = stringResource(
    when (stage) {
        BackgroundWork.Stage.Artwork -> R.string.progress_stage_artwork
        BackgroundWork.Stage.Lyrics -> R.string.progress_stage_lyrics
        BackgroundWork.Stage.ArtistImages -> R.string.progress_stage_artist_images
        BackgroundWork.Stage.ReplayGain -> R.string.progress_stage_replay_gain
        BackgroundWork.Stage.Durations -> R.string.progress_stage_durations
        BackgroundWork.Stage.Colors -> R.string.progress_stage_colors
    },
)

/**
 * Slim determinate wavy bar + caption ("Getting artwork 120 of 583") for background enrichment. Expands
 * and fades in while [work] is non-null and collapses when it finishes; TalkBack announces only stage
 * changes (polite live region on the stage name, not the count).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BackgroundWorkProgress(work: BackgroundWork?, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(work) }
    if (work != null) last = work
    val reduceMotion = LocalReduceMotion.current
    AnimatedVisibility(
        visible = work != null,
        enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        label = "bgWork",
    ) {
        val w = last ?: return@AnimatedVisibility
        val fraction by animateFloatAsState(
            if (w.total > 0) w.done.toFloat() / w.total else 0f,
            MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "bgWorkFraction",
        )
        val label = stageLabel(w.stage)
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.progress_stage_count, label, w.done, w.total),
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                },
            )
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                amplitude = { if (reduceMotion) 0f else 1f },
                waveSpeed = if (reduceMotion) 0.dp else WavyProgressIndicatorDefaults.LinearDeterminateWavelength / 2,
            )
        }
    }
}

@Composable
private fun TitleRow(
    userName: String?,
    tab: LibraryTab,
    state: HomeTabState,
    onRecentlyPlayed: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
) {
    val title = if (userName != null) stringResource(greetingRes(), userName) else stringResource(R.string.nav_title_library)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.6).sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                )
                Spacer(Modifier.width(8.dp))
                DevBadge()
            }
            Counts(tab, state, Modifier.padding(top = 2.dp))
        }
        HeaderAction(Icons.Rounded.History, stringResource(R.string.home_action_recently_played), onRecentlyPlayed)
        HeaderAction(Icons.Rounded.FavoriteBorder, stringResource(R.string.home_action_favorites), onFavorites)
        HeaderAction(Icons.Rounded.Settings, stringResource(R.string.home_action_settings), onSettings)
    }
}

/** Count and total time side by side in tabular figures; no separators. */
@Composable
private fun Counts(tab: LibraryTab, s: HomeTabState, modifier: Modifier = Modifier) {
    val count = when (tab) {
        LibraryTab.Songs -> pluralStringResource(R.plurals.song_count, s.count, s.count)
        LibraryTab.Albums -> pluralStringResource(R.plurals.album_count, s.count, s.count)
        LibraryTab.Artists -> pluralStringResource(R.plurals.artist_count, s.count, s.count)
        LibraryTab.Playlists -> pluralStringResource(R.plurals.playlist_count, s.count, s.count)
    }
    val ctx = LocalContext.current
    @Suppress("DEPRECATION") val locale = LocalConfiguration.current.locales[0]
    val total = s.totalDurationMs.takeIf { it > 0 }?.let { DurationFormat.totalText(ctx, it / 1000, locale) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(count, style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.onSurface)
        if (total != null) Text(total, style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionGroup(onSearch: () -> Unit, onSort: () -> Unit, onShuffle: () -> Unit, tab: LibraryTab) {
    val cs = MaterialTheme.colorScheme
    val neutral = ButtonDefaults.buttonColors(containerColor = cs.surfaceContainerHigh, contentColor = cs.onSurface)
    val search = stringResource(R.string.home2_search)
    val sort = stringResource(R.string.home2_sort)
    val shuffle = stringResource(R.string.home2_shuffle)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        // Labels only when the widest one fits its third of the row (icon 18 + gap 8 + padding 2 x 16, plus slack);
        // otherwise icon-only buttons with descriptions, never ellipsised text.
        val widest = with(density) { listOf(search, sort, shuffle).maxOf { measurer.measure(it, style).size.width }.toDp() }
        val perButton = (maxWidth - ButtonGroupDefaults.ConnectedSpaceBetween * 2) / 3
        val showLabels = perButton >= widest + 66.dp
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            GroupButton(Icons.Rounded.Search, search, ButtonGroupDefaults.connectedLeadingButtonShapes().shape, neutral, onSearch, showLabel = showLabels)
            GroupButton(
                Icons.AutoMirrored.Rounded.Sort, sort,
                ButtonGroupDefaults.connectedMiddleButtonShapes().shape, neutral, onSort,
                description = stringResource(R.string.home_sort_button, stringResource(tab.labelRes)),
                showLabel = showLabels,
            )
            GroupButton(Icons.Rounded.Shuffle, shuffle, ButtonGroupDefaults.connectedTrailingButtonShapes().shape,
                ButtonDefaults.buttonColors(), onShuffle, showLabel = showLabels)
        }
    }
}

@Composable
private fun RowScope.GroupButton(
    icon: ImageVector,
    label: String,
    shape: Shape,
    colors: androidx.compose.material3.ButtonColors,
    onClick: () -> Unit,
    description: String? = null,
    showLabel: Boolean = true,
) {
    Button(
        onClick = onClick,
        shape = shape,
        colors = colors,
        contentPadding = ButtonDefaults.SmallContentPadding,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).then(
            if (description != null || !showLabel) Modifier.semantics { this.contentDescription = description ?: label } else Modifier,
        ),
    ) {
        if (showLabel) {
            Icon(icon, null, Modifier.padding(end = 8.dp))
            Text(label, maxLines = 1)
        } else {
            Icon(icon, null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}
