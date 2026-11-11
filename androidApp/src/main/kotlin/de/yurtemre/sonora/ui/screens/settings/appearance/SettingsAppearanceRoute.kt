package de.yurtemre.sonora.ui.screens.settings.appearance

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.rounded.ArtTrack
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.yurtemre.sonora.R
import de.yurtemre.sonora.shared.library.SeedColorBuckets
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.NowPlayingStyle
import de.yurtemre.sonora.shared.model.ThemeColorSource
import de.yurtemre.sonora.shared.model.ThemeModeSetting
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.ui.screens.settings.ChoiceRow
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
fun SettingsAppearanceRoute(onBack: () -> Unit) {
    val repo: SettingsRepository = koinInject()
    val library: LibraryRepository = koinInject()
    val settings by repo.settings.collectAsStateWithLifecycle(AppSettings())
    val seeds by remember(library) { library.songs.map { songs -> songs.mapNotNull { it.seedColor } } }
        .collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var nameDialog by rememberSaveable { mutableStateOf(false) }
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

        settingsHeader("now_playing") { stringResource(R.string.settings_section_now_playing) }
        item(key = "np_style") {
            ChoiceRow(
                title = stringResource(R.string.settings_np_style_title),
                options = NowPlayingStyle.entries,
                selected = settings.nowPlayingStyle,
                label = { stringResource(it.labelRes) },
                onSelect = { save { setNowPlayingStyle(it) } },
                icon = Icons.Rounded.ArtTrack,
            )
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

        settingsHeader("personal") { stringResource(R.string.settings_section_personal) }
        item(key = "greeting") {
            SwitchRow(
                stringResource(R.string.settings_greeting), settings.useGreetingTitle,
                { save { setUseGreetingTitle(it) } }, icon = Icons.Rounded.WbSunny,
                subtitle = stringResource(R.string.settings_greeting_subtitle),
            )
        }
        item(key = "name") {
            AnimatedVisibility(settings.useGreetingTitle) {
                SettingsRow(
                    stringResource(R.string.settings_name), icon = Icons.Rounded.Badge,
                    subtitle = settings.userName, onClick = { nameDialog = true },
                )
            }
        }
    }

    if (nameDialog) {
        val fallback = stringResource(R.string.settings_name_default)
        NameDialog(
            initial = settings.userName,
            onSave = { name ->
                nameDialog = false
                save { setUserName(name.trim().ifEmpty { fallback }) }
            },
            onDismiss = { nameDialog = false },
        )
    }
}

@Composable
private fun NameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_name_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_name)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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

private val NowPlayingStyle.labelRes: Int get() = when (this) {
    NowPlayingStyle.Modern -> R.string.settings_np_style_modern
    NowPlayingStyle.Vinyl -> R.string.settings_np_style_vinyl
    NowPlayingStyle.Minimalist -> R.string.settings_np_style_minimalist
}
