package de.yurtemre.sonora

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.ThemeColorSource
import de.yurtemre.sonora.shared.model.ThemeModeSetting
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.navigation.LibraryTab
import de.yurtemre.sonora.ui.navigation.SonoraAppShell
import de.yurtemre.sonora.ui.theme.SonoraTheme
import de.yurtemre.sonora.ui.theme.ThemeColorSourceUi
import de.yurtemre.sonora.ui.theme.ThemeModeUi
import org.koin.android.ext.android.inject

/** AppCompatActivity for per-app language (L10N-03). Hosts the gate (TODO P5-01) and then the shell. */
class MainActivity : AppCompatActivity() {
    private val settingsRepository: SettingsRepository by inject()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
            val windowSize = calculateWindowSizeClass(this)
            SonoraTheme(
                colorSource = settings.themeColorSource.toUi(),
                themeMode = settings.themeMode.toUi(),
                amoled = settings.amoledDark,
            ) {
                // TODO(P5-01): GateHost until Ready; then the shell.
                SonoraAppShell(
                    widthSizeClass = windowSize.widthSizeClass,
                    defaultTab = LibraryTab.fromStartPage(settings.defaultStartPage),
                )
            }
        }
    }
}

private fun ThemeColorSource.toUi() = when (this) {
    ThemeColorSource.Afterglow -> ThemeColorSourceUi.Afterglow
    ThemeColorSource.System -> ThemeColorSourceUi.System
    ThemeColorSource.AlbumArt -> ThemeColorSourceUi.AlbumArt
    ThemeColorSource.Custom -> ThemeColorSourceUi.Custom
}

private fun ThemeModeSetting.toUi() = when (this) {
    ThemeModeSetting.System -> ThemeModeUi.System
    ThemeModeSetting.Light -> ThemeModeUi.Light
    ThemeModeSetting.Dark -> ThemeModeUi.Dark
}
