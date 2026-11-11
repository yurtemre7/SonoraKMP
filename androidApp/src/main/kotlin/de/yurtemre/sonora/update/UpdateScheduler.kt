package de.yurtemre.sonora.update

import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.shared.update.UpdateChecker
import kotlinx.coroutines.flow.first
import java.util.Collections

/**
 * Launch-check policy (UPD-05): release builds only, `auto_check_updates` on, at most once per 6 h (persisted last
 * check) and not before a stored rate-limit reset. The sheet is shown at most once per version per process.
 */
class UpdateScheduler(
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val autoCheckAllowed: Boolean,
) {
    suspend fun isAutoCheckDue(): Boolean {
        if (!autoCheckAllowed) return false
        val s = settings.settings.first()
        return s.autoCheckUpdates &&
            UpdateChecker.shouldAutoCheck(clock.nowMs(), s.lastUpdateCheckMs, s.updateRateLimitResetMs)
    }

    /** True the first time [version] is offered in this process. */
    fun markPrompted(version: String): Boolean = promptedVersions.add(version)

    private companion object {
        val promptedVersions: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }
}
