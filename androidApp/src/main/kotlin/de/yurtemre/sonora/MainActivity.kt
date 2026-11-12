package de.yurtemre.sonora

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.ThemeColorSource
import de.yurtemre.sonora.shared.model.ThemeModeSetting
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.components.DisplayPrefs
import de.yurtemre.sonora.ui.components.LocalDisplayPrefs
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.SonoraAppShell
import de.yurtemre.sonora.ui.screens.gate.GateHost
import de.yurtemre.sonora.ui.theme.SonoraTheme
import de.yurtemre.sonora.ui.theme.ThemeColorSourceUi
import de.yurtemre.sonora.ui.theme.ThemeModeUi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.android.ext.android.inject

/** AppCompatActivity for per-app language (L10N-03). Hosts the startup gate (UI-01) → onboarding → shell. */
class MainActivity : AppCompatActivity() {
    private val settingsRepository: SettingsRepository by inject()
    private val playback: PlaybackConnection by inject()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
            val windowSize = calculateWindowSizeClass(this)
            val songSeed by remember { playback.player.map { it.currentSong?.seedColor }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(null)
            // Album art: the current song's seed, the chosen swatch as fallback; Custom: the chosen swatch.
            val seed = when (settings.themeColorSource) {
                ThemeColorSource.AlbumArt -> Color(songSeed ?: settings.defaultThemeColor.toInt())
                ThemeColorSource.Custom -> Color(settings.defaultThemeColor.toInt())
                else -> null
            }
            SonoraTheme(
                colorSource = settings.themeColorSource.toUi(),
                themeMode = settings.themeMode.toUi(),
                amoled = settings.amoledDark,
                seedColor = seed,
            ) {
                val prefs = DisplayPrefs(settings.filterTitleFeatures, settings.filterTitleArtist, settings.preferLocalArtistImages)
                CompositionLocalProvider(LocalDisplayPrefs provides prefs) {
                    GateHost {
                        SonoraAppShell(
                            widthSizeClass = windowSize.widthSizeClass,
                            defaultTab = LibraryTab.fromStartPage(settings.defaultStartPage),
                        )
                    }
                }
            }
        }
    }
}

private fun ThemeColorSource.toUi() = when (this) {
    ThemeColorSource.Afterglow -> ThemeColorSourceUi.Sleeve
    ThemeColorSource.System -> ThemeColorSourceUi.System
    ThemeColorSource.AlbumArt -> ThemeColorSourceUi.AlbumArt
    ThemeColorSource.Custom -> ThemeColorSourceUi.Custom
}

private fun ThemeModeSetting.toUi() = when (this) {
    ThemeModeSetting.System -> ThemeModeUi.System
    ThemeModeSetting.Light -> ThemeModeUi.Light
    ThemeModeSetting.Dark -> ThemeModeUi.Dark
}
