package de.yurtemre.sonora.shared.devicesync

/** Device-specific stored settings that device sync never sends nor applies (DSYNC-05, plan Global Constraints). */
object DeviceSyncSettings {
    val EXCLUDED_KEYS: Set<String> = setOf(
        "scan_folder_path", "scan_folder_uri", "volume", "onboarding_completed", "last_update_check_ms",
        "update_rate_limit_reset_ms", "last_sync_timestamp", "last_sync_duration_ms", "postpone_sync_until",
        "colorless_artwork_paths", "repeat_mode",
    )
    val EXCLUDED_PREFIXES: List<String> = listOf("last_played_", "device_sync_")

    fun isExcluded(storedKey: String): Boolean =
        storedKey in EXCLUDED_KEYS || EXCLUDED_PREFIXES.any { storedKey.startsWith(it) }
}
