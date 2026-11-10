package de.yurtemre.sonora.shared.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile

/** DataStore file name for all settings + playback state (SPEC §5.3). */
const val SETTINGS_DATASTORE_NAME = "sonora_settings"

/** Creates the single settings DataStore. Must be a process-wide singleton (bind in Koin `single`). */
fun createSettingsDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        produceFile = { context.applicationContext.preferencesDataStoreFile(SETTINGS_DATASTORE_NAME) },
    )
