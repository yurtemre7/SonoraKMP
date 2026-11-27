package de.yurtemre.sonora.ui.screens.settings.appearance

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.SeedColorBuckets
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.ThemeColorSource
import de.yurtemre.sonora.shared.model.ThemeModeSetting
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.screens.settings.ConnectedChoice
import de.yurtemre.sonora.ui.screens.settings.SettingsNote
import de.yurtemre.sonora.ui.screens.settings.SettingsRow
import de.yurtemre.sonora.ui.screens.settings.SettingsScaffold
import de.yurtemre.sonora.ui.screens.settings.SwitchRow
import de.yurtemre.sonora.ui.screens.settings.settingsHeader
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val DynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
private const val EMBER = 0xFFE5573B.toInt()

/** SET-02 / UI-33: theme mode, colour source + swatches, AMOLED, blur, now playing look, greeting. */
@Composable
fun SettingsAppearanceRoute(onBack: () -> Unit, onOpenHomeLayout: () -> Unit) {
    val repo: SettingsRepository = koinInject()
    val library: LibraryRepository = koinInject()
    val settings by repo.settings.collectAsStateWithLifecycle(AppSettings())
    val seeds by remember(library) { library.songs.map { songs -> songs.mapNotNull { it.seedColor } } }
        .collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    fun save(block: suspend SettingsRepository.() -> Unit) { scope.launch { repo.block() } }

    val selectedSeed = settings.defaultThemeColor.toInt()
    val swatches = remember(seeds, selectedSeed) {
        val base = SeedColorBuckets.uniqueSwatches(seeds, EMBER)
        if (selectedSeed in base) base else base + selectedSeed
    }

    SettingsScaffold(stringResource(R.string.settings_appearance_title), onBack) {
        settingsHeader("theme") { stringResource(R.string.settings_section_theme) }
        item(key = "theme_mode") {
            ConnectedChoice(
                options = ThemeModeSetting.entries,
                selected = settings.themeMode,
                onSelect = { save { setThemeMode(it) } },
                label = { stringResource(it.labelRes) },
                icon = { it.icon },
            )
        }

        settingsHeader("colors") { stringResource(R.string.settings_section_colors) }
        item(key = "color_source") {
            ConnectedChoice(
                options = ColorSources,
                selected = settings.themeColorSource,
                onSelect = { save { setThemeColorSource(it) } },
                label = { stringResource(it.labelRes) },
                enabled = { it != ThemeColorSource.System || DynamicColorSupported },
            )
        }
        item(key = "color_desc") {
            AnimatedContent(settings.themeColorSource, label = "colorDesc") { source ->
                SettingsNote(stringResource(source.descRes))
            }
        }
        item(key = "swatches") {
            val source = settings.themeColorSource
            AnimatedVisibility(source == ThemeColorSource.AlbumArt || source == ThemeColorSource.Custom) {
                Column {
                    SettingsRow(
                        stringResource(
                            if (source == ThemeColorSource.AlbumArt) R.string.settings_fallback_color
                            else R.string.settings_accent_color,
                        ),
                        icon = Icons.Rounded.Colorize,
                    )
                    SeedSwatchRow(swatches, selectedSeed, onSelect = { argb ->
                        save { setDefaultThemeColor(argb.toLong() and 0xFFFFFFFFL) }
                    })
                    if (swatches.size <= 1) SettingsNote(stringResource(R.string.settings_swatch_hint))
                }
            }
        }

        settingsHeader("display") { stringResource(R.string.settings_section_display) }
        item(key = "amoled") {
            SwitchRow(
                stringResource(R.string.settings_amoled_title), settings.amoledDark, { save { setAmoledDark(it) } },
                icon = Icons.Outlined.DarkMode, subtitle = stringResource(R.string.settings_amoled_subtitle),
            )
        }
        item(key = "blur") {
            SwitchRow(
                stringResource(R.string.settings_blur_title),
                settings.blurredBackgrounds && DynamicColorSupported,
                { save { setBlurredBackgrounds(it) } },
                icon = Icons.Rounded.BlurOn,
                subtitle = stringResource(
                    if (DynamicColorSupported) R.string.settings_blur_subtitle else R.string.settings_requires_android_12,
                ),
                enabled = DynamicColorSupported,
            )
        }

        settingsHeader("home") { stringResource(R.string.settings_section_home) }
        item(key = "home_layout") {
            SettingsRow(
                stringResource(R.string.home_layout_title),
                icon = Icons.Rounded.ViewAgenda,
                subtitle = stringResource(R.string.home_layout_row_subtitle),
                chevron = true,
                onClick = onOpenHomeLayout,
            )
        }

        settingsHeader("now_playing") { stringResource(R.string.settings_section_now_playing) }
        item(key = "np_style") {
            PlayerStylePicker(settings.nowPlayingStyle, onSelect = { save { setNowPlayingStyle(it) } })
        }
        item(key = "title_style") {
            TitleStylePicker(settings.titleStyle, onSelect = { save { setTitleStyle(it) } })
        }
        item(key = "local_images") {
            SwitchRow(
                stringResource(R.string.settings_local_artist_images), settings.preferLocalArtistImages,
                { save { setPreferLocalArtistImages(it) } }, icon = Icons.Outlined.AccountBox,
                subtitle = stringResource(R.string.settings_local_artist_images_subtitle),
            )
        }
        item(key = "visualizer") {
            SwitchRow(
                stringResource(R.string.settings_visualizer), settings.showVisualizer,
                { save { setShowVisualizer(it) } }, icon = Icons.Rounded.BarChart,
                subtitle = stringResource(R.string.settings_visualizer_subtitle),
            )
        }
    }

}

private val ColorSources = listOf(
    ThemeColorSource.Afterglow, ThemeColorSource.System, ThemeColorSource.AlbumArt, ThemeColorSource.Custom,
)

private val ThemeModeSetting.labelRes: Int get() = when (this) {
    ThemeModeSetting.System -> R.string.settings_theme_system
    ThemeModeSetting.Light -> R.string.settings_theme_light
    ThemeModeSetting.Dark -> R.string.settings_theme_dark
}

private val ThemeModeSetting.icon: ImageVector get() = when (this) {
    ThemeModeSetting.System -> Icons.Rounded.BrightnessAuto
    ThemeModeSetting.Light -> Icons.Rounded.LightMode
    ThemeModeSetting.Dark -> Icons.Rounded.DarkMode
}

private val ThemeColorSource.labelRes: Int get() = when (this) {
    ThemeColorSource.Afterglow -> R.string.settings_color_sleeve
    ThemeColorSource.System -> R.string.settings_color_system
    ThemeColorSource.AlbumArt -> R.string.settings_color_album
    ThemeColorSource.Custom -> R.string.settings_color_custom
}

private val ThemeColorSource.descRes: Int get() = when (this) {
    ThemeColorSource.Afterglow -> R.string.settings_color_sleeve_desc
    ThemeColorSource.System -> R.string.settings_color_system_desc
    ThemeColorSource.AlbumArt -> R.string.settings_color_album_desc
    ThemeColorSource.Custom -> R.string.settings_color_custom_desc
}

