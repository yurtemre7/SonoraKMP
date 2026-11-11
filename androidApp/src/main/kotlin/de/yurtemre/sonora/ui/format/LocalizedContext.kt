package de.yurtemre.sonora.ui.format

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * L10N-06: services, widgets and notifications don't get AppCompat's per-app locale applied automatically.
 * Wrap their context so `getString` resolves with the app locale.
 */
fun Context.withAppLocale(): Context {
    val tags = AppCompatDelegate.getApplicationLocales()
    if (tags.isEmpty) return this
    val conf = Configuration(resources.configuration)
    conf.setLocales(LocaleList.forLanguageTags(tags.toLanguageTags()))
    return createConfigurationContext(conf)
}

/** The locale strings/dates should use outside Compose. */
fun Context.appLocale(): Locale =
    AppCompatDelegate.getApplicationLocales().get(0) ?: resources.configuration.locales[0]
