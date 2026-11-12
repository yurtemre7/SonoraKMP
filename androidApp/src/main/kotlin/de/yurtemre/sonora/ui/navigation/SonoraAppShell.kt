package de.yurtemre.sonora.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.ui.components.MiniPlayer
import de.yurtemre.sonora.ui.components.bottomSafePadding
import de.yurtemre.sonora.ui.components.padAndConsume
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailItemDefaults
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import de.yurtemre.sonora.ui.screens.nowplaying.NowPlayingTwoPane
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import de.yurtemre.sonora.ui.navigation.SonoraTransitions.pop
import de.yurtemre.sonora.ui.navigation.SonoraTransitions.predictivePop
import de.yurtemre.sonora.ui.navigation.SonoraTransitions.push
import de.yurtemre.sonora.ui.screens.album.AlbumRoute
import de.yurtemre.sonora.ui.screens.artist.ArtistRoute
import de.yurtemre.sonora.ui.screens.changelog.ChangelogRoute
import de.yurtemre.sonora.ui.screens.debug.DebugCachesRoute
import de.yurtemre.sonora.ui.screens.favorites.FavoritesRoute
import de.yurtemre.sonora.ui.screens.home.HomeRoute
import de.yurtemre.sonora.ui.screens.licenses.LicensesRoute
import de.yurtemre.sonora.ui.screens.playlist.PlaylistRoute
import de.yurtemre.sonora.ui.screens.recent.RecentlyPlayedRoute
import de.yurtemre.sonora.ui.screens.settings.SettingsRoute
import de.yurtemre.sonora.ui.screens.settings.appearance.SettingsAppearanceRoute
import de.yurtemre.sonora.ui.screens.settings.backup.SettingsBackupRoute
import de.yurtemre.sonora.ui.screens.settings.formatting.SettingsFormattingRoute
import de.yurtemre.sonora.ui.screens.settings.info.SettingsInfoRoute
import de.yurtemre.sonora.ui.screens.settings.playback.SettingsPlaybackRoute
import de.yurtemre.sonora.ui.screens.settings.privacy.SettingsPrivacyRoute
import de.yurtemre.sonora.ui.screens.stats.StatsRoute

/** Pops everything above the root `Library` entry (tab selection itself is state, not navigation; SPEC §6). */
private fun NavBackStack<NavKey>.popToLibrary() {
    while (size > 1) removeAt(lastIndex)
}

/**
 * App shell: adaptive navigation (DESIGN §6), Navigation 3 display (SPEC §6) and the Now Playing overlay slot.
 * Gate is hosted outside (MainActivity) and is not part of the back stack.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SonoraAppShell(
    widthSizeClass: WindowWidthSizeClass,
    defaultTab: LibraryTab,
    onOpenNowPlaying: (() -> Unit)? = null,
    backStack: NavBackStack<NavKey> = rememberNavBackStack(Library),
    nowPlaying: NowPlayingSlotState = rememberNowPlayingSlotState(),
) {
    val controller = rememberNowPlayingController(nowPlaying)
    val openNowPlaying: () -> Unit = onOpenNowPlaying ?: controller::expand
    val playback: PlaybackConnection = koinInject()
    val player by playback.player.collectAsStateWithLifecycle()
    val sleepTimer by playback.sleepTimer.collectAsStateWithLifecycle()
    var showMiniSleep by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showMiniSleep) de.yurtemre.sonora.ui.screens.nowplaying.sheets.SleepTimerSheet(
        onDismiss = { showMiniSleep = false },
        connection = playback,
    )
    // Kept as a State (not delegated): only the mini player's progress line reads it, so position ticks
    // invalidate one draw call instead of recomposing the shell (P5-26).
    val position = playback.positionMs.collectAsStateWithLifecycle(0L)
    val flingPx = with(androidx.compose.ui.platform.LocalDensity.current) { 600.dp.toPx() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val closedMsg = stringResource(R.string.miniplayer_closed)
    val undoLabel = stringResource(R.string.miniplayer_undo)
    val mini: @Composable () -> Unit = {
        if (player.currentSong != null) MiniPlayer(
            state = player,
            progress = { if (player.durationMs > 0) position.value.toFloat() / player.durationMs else 0f },
            onOpen = openNowPlaying,
            expandProgress = { nowPlaying.fraction },
            onExpandDrag = nowPlaying::dragBy,
            onExpandDragStopped = { v -> nowPlaying.settle(v, flingPx) },
            sleepTimer = sleepTimer,
            onSleepTimerClick = { showMiniSleep = true },
            onTogglePlay = { if (player.isCompleted) { playback.seekTo(0); playback.play() } else playback.togglePlayPause() },
            onNext = playback::skipToNext,
            onPrevious = playback::skipToPrevious,
            onClose = {
                playback.closePlayer()
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar(closedMsg, undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                        playback.undoClosePlayer()
                    }
                }
            },
        )
    }
    var rootTab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(defaultTab) }
    val showNav = backStack.size == 1
    val onTab: (LibraryTab) -> Unit = {
        if (it == rootTab && backStack.size == 1) de.yurtemre.sonora.ui.screens.home.HomeTabReselect.events.tryEmit(it)
        else { backStack.popToLibrary(); rootTab = it }
    }
    val navigate: (NavKey) -> Unit = { backStack.add(it) }
    val goBack: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    // Back rule 6: root tab ≠ default → switch to default; otherwise the activity finishes.
    BackHandler(enabled = backStack.size == 1 && rootTab != defaultTab && !nowPlaying.expanded) {
        rootTab = defaultTab
    }

    val display: @Composable (Modifier, List<NavKey>) -> Unit = { modifier, stack ->
        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = goBack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
                remember { predictiveBackCorners() },
            ),
            transitionSpec = { push() },
            popTransitionSpec = { pop() },
            predictivePopTransitionSpec = { predictivePop() },
            entryProvider = entryProvider {
                entry<Library> { HomeRoute(rootTab, navigate) }
                entry<Album> { AlbumRoute(it.name, it.artist, goBack, navigate) }
                entry<Artist> { ArtistRoute(it.name, goBack, onOpenAlbum = { n, a -> navigate(Album(n, a)) }, onNavigate = navigate) }
                entry<Playlist> { PlaylistRoute(it.id, goBack, onNavigate = navigate) }
                entry<Favorites> { FavoritesRoute(goBack, navigate) }
                entry<RecentlyPlayed> { RecentlyPlayedRoute(goBack, navigate) }
                entry<Stats> { StatsRoute(goBack, navigate) }
                entry<Settings> { TextWidth { SettingsRoute(goBack, navigate, onOpenUpdates = { navigate(SettingsInfo) }) } }
                entry<SettingsAppearance> { TextWidth { SettingsAppearanceRoute(goBack) } }
                entry<SettingsFormatting> { TextWidth { SettingsFormattingRoute(goBack) } }
                entry<SettingsPlayback> { TextWidth { SettingsPlaybackRoute(goBack) } }
                entry<SettingsPrivacy> { TextWidth { SettingsPrivacyRoute(goBack) } }
                entry<SettingsBackup> { TextWidth { SettingsBackupRoute(goBack) } }
                entry<SettingsInfo> { TextWidth { SettingsInfoRoute(goBack, navigate) } }
                entry<Changelog> { TextWidth { ChangelogRoute(goBack) } }
                entry<Licenses> { TextWidth { LicensesRoute(goBack) } }
                entry<DebugCaches> { DebugCachesRoute(goBack) }
            },
        )
    }

    val overlayMode = widthSizeClass != WindowWidthSizeClass.Expanded
    // Expanded widths: the detail pane shows only backStack.drop(1); with a single detail entry NavDisplay has nothing
    // to pop, so the shell closes the detail pane (detail first, then the list). Registered before the panes so any
    // screen handler inside the detail (selection, search) still wins.
    BackHandler(enabled = !overlayMode && backStack.size == 2 && !nowPlaying.expanded) { goBack() }
    CompositionLocalProvider(LocalNowPlayingController provides controller) { Box(Modifier.fillMaxSize()) {
        // While the overlay covers the shell, TalkBack must not reach the content behind it (P6-03).
        Box(Modifier.fillMaxSize().then(if (overlayMode && nowPlaying.expanded) Modifier.clearAndSetSemantics {} else Modifier)) {
        when (widthSizeClass) {
            WindowWidthSizeClass.Compact -> Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = { Column(if (showNav) Modifier else Modifier.bottomSafePadding()) {
                    mini()
                    if (showNav) ShortNavigationBar {
                        LibraryTab.entries.forEach { tab ->
                            ShortNavigationBarItem(
                                colors = ShortNavigationBarItemDefaults.colors().let { it.copy(it.selectedIconColor, MaterialTheme.colorScheme.onSurface) },
                                selected = rootTab == tab,
                                onClick = { onTab(tab) },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(stringResource(tab.labelRes)) },
                            )
                        }
                    }
                } },
            ) { padding -> display(Modifier.fillMaxSize().padAndConsume(PaddingValues(bottom = padding.calculateBottomPadding())), backStack) }

            WindowWidthSizeClass.Medium -> Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
                    windowInsets = WindowInsets(0),
                ) {
                    LibraryTab.entries.forEach { tab ->
                        NavigationRailItem(
                            colors = NavigationRailItemDefaults.colors(selectedTextColor = MaterialTheme.colorScheme.onSurface),
                            selected = rootTab == tab,
                            onClick = { onTab(tab) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
                RailContent(hasMini = player.currentSong != null, display = { display(it, backStack) }, mini = mini)
            }

            else -> Row(Modifier.fillMaxSize()) {
                WideNavigationRail(
                    state = androidx.compose.material3.rememberWideNavigationRailState(androidx.compose.material3.WideNavigationRailValue.Expanded),
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
                    windowInsets = WindowInsets(0),
                ) {
                    LibraryTab.entries.forEach { tab ->
                        WideNavigationRailItem(
                            colors = WideNavigationRailItemDefaults.colors().let { it.copy(it.selectedIconColor, MaterialTheme.colorScheme.onSurface) },
                            selected = rootTab == tab,
                            onClick = { onTab(tab) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(stringResource(tab.labelRes)) },
                            railExpanded = true,
                        )
                    }
                }
                ListDetail(
                    list = { m -> RailContent(hasMini = player.currentSong != null && !nowPlaying.expanded, display = { Box(it) { HomeRoute(rootTab, navigate) } }, mini = mini, modifier = m) },
                    detail = when {
                        nowPlaying.expanded && player.currentSong != null -> { m ->
                            NowPlayingTwoPane(
                                onCollapse = { nowPlaying.expanded = false },
                                onOpenAlbum = { nowPlaying.expanded = false; navigate(albumKeyOf(it)) },
                                onOpenArtist = { nowPlaying.expanded = false; navigate(artistKeyOf(it)) },
                                snackbarHostState = snackbar,
                                modifier = m.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)),
                            )
                        }
                        backStack.size > 1 -> { m -> display(m, backStack.drop(1)) }
                        else -> null
                    },
                    spineDivider = nowPlaying.expanded && player.currentSong != null,
                )
            }
        }
        if (widthSizeClass != WindowWidthSizeClass.Compact) {
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).bottomSafePadding()
                    .padding(bottom = if (player.currentSong != null) 72.dp else 0.dp),
            )
        }
        }
        if (overlayMode) NowPlayingSlot(nowPlaying, snackbarHostState = snackbar, onNavigate = navigate)
        // Expanded widths show Now Playing as a pane; back collapses it there. Composed (registered) only while
        // expanded and after the panes, so it is the topmost callback and beats the list's handlers.
        if (!overlayMode && nowPlaying.expanded) BackHandler { nowPlaying.expanded = false }
        // Automatic launch update check + update sheet (UPD-05); placed once.
        de.yurtemre.sonora.update.UpdatePromptHost()
    } }
}


/**
 * Content column beside a navigation rail. The rail already pads the start side; the mini player keeps clear of the
 * navigation bar itself and, when shown, consumes the bottom inset so screens above it don't pad for it twice.
 */
@Composable
private fun RowScope.RailContent(hasMini: Boolean, display: @Composable (Modifier) -> Unit, mini: @Composable () -> Unit) =
    RailContent(hasMini, display, mini, Modifier.weight(1f))

@Composable
private fun RailContent(hasMini: Boolean, display: @Composable (Modifier) -> Unit, mini: @Composable () -> Unit, modifier: Modifier) {
    val navBottom = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)
    Column(modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))) {
        display(Modifier.weight(1f).then(if (hasMini) Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier))
        if (hasMini) Box(Modifier.windowInsetsPadding(navBottom)) { mini() }
    }
}
