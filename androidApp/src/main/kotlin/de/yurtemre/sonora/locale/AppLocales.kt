package de.yurtemre.sonora.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import de.yurtemre.sonora.shared.model.AppLocale

/** Per-app language (L10N-03) via AppCompat; the activity recreates itself when the list changes. */
object AppLocales {
    fun apply(locale: AppLocale) {
        val list = when (locale) {
            AppLocale.System -> LocaleListCompat.getEmptyLocaleList()
            AppLocale.English -> LocaleListCompat.forLanguageTags("en")
            AppLocale.Japanese -> LocaleListCompat.forLanguageTags("ja")
        }
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != list.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(list)
        }
    }

    /** The locale AppCompat currently applies (source of truth after a system-settings change). */
    fun current(): AppLocale {
        val tag = AppCompatDelegate.getApplicationLocales().get(0)?.language
        return when (tag) {
            "en" -> AppLocale.English
            "ja" -> AppLocale.Japanese
            else -> AppLocale.System
        }
    }
}
