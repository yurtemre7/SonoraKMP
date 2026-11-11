package de.yurtemre.sonora.update

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.yurtemre.sonora.BuildConfig
import de.yurtemre.sonora.shared.model.UpdateChannel
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.shared.update.GitHubReleasesClient
import de.yurtemre.sonora.shared.update.UpdateCheckResult
import de.yurtemre.sonora.shared.update.UpdateChecker
import de.yurtemre.sonora.shared.update.UpdateFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Download + install of an [UpdateCheckResult.Available] release (§7, P7).
 * TODO(P7-01..03): real implementation (ApkDownloader, Sha256Verifier, PackageInstallerHelper) replaces [Stub].
 */
interface UpdateInstaller {
    /** False while in-app installs are not implemented; the UI then falls back to the browser. */
    val isSupported: Boolean
    suspend fun downloadAndInstall(update: UpdateCheckResult.Available)

    object Stub : UpdateInstaller {
        override val isSupported: Boolean = false
        override suspend fun downloadAndInstall(update: UpdateCheckResult.Available) = Unit
    }
}

/** UI state of the updater (§7 state machine; download states arrive with P7). */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val update: UpdateCheckResult.Available) : UpdateUiState
    data class Failed(val reason: UpdateFailure) : UpdateUiState
}

class UpdateViewModel(
    client: GitHubReleasesClient,
    private val clock: Clock,
    private val settings: SettingsRepository,
    private val installer: UpdateInstaller,
    versionName: String,
    private val autoCheckAllowed: Boolean,
) : ViewModel() {
    private val checker = UpdateChecker(client, clock, versionName)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** Release shown by [UpdatePromptHost] after an automatic check; null when nothing to prompt. */
    private val _prompt = MutableStateFlow<UpdateCheckResult.Available?>(null)
    val prompt: StateFlow<UpdateCheckResult.Available?> = _prompt.asStateFlow()

    val channel: StateFlow<UpdateChannel> = settings.settings.map { it.updateChannel }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UpdateChannel.Stable)
    val lastCheckMs: StateFlow<Long> = settings.settings.map { it.lastUpdateCheckMs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val installSupported: Boolean get() = installer.isSupported

    private var job: Job? = null

    /** Settings › Info › Check for updates: always runs, ignores throttle (UPD-07). */
    fun checkManually() {
        job?.cancel()
        job = viewModelScope.launch { _state.value = UpdateUiState.Checking; run(prompt = false) }
    }

    /** Cold-start check (UPD-05): release builds, `auto_check_updates`, ≤ 1 per 6 h, after the rate-limit reset. */
    fun autoCheckIfDue() {
        if (!autoCheckAllowed || job?.isActive == true) return
        job = viewModelScope.launch {
            val s = settings.settings.first()
            if (!s.autoCheckUpdates) return@launch
            if (!UpdateChecker.shouldAutoCheck(clock.nowMs(), s.lastUpdateCheckMs, s.updateRateLimitResetMs)) return@launch
            run(prompt = true)
        }
    }

    private suspend fun run(prompt: Boolean) {
        val result = checker.check(settings.settings.first().updateChannel)
        val now = clock.nowMs()
        when (result) {
            UpdateCheckResult.UpToDate -> {
                settings.setLastUpdateCheckMs(now)
                _state.value = UpdateUiState.UpToDate
            }
            is UpdateCheckResult.Available -> {
                settings.setLastUpdateCheckMs(now)
                _state.value = UpdateUiState.Available(result)
                if (prompt && promptedVersions.add(result.version.toString())) _prompt.value = result
            }
            is UpdateCheckResult.Failed -> {
                when (val r = result.reason) {
                    is UpdateFailure.RateLimited -> settings.setUpdateRateLimitResetMs(r.resetAtMs)
                    UpdateFailure.NoAsset, UpdateFailure.Malformed -> settings.setLastUpdateCheckMs(now) // HTTP 200
                    else -> Unit
                }
                // Automatic checks are silent unless an update is available (§7 "When").
                _state.value = if (prompt) UpdateUiState.Idle else UpdateUiState.Failed(result.reason)
            }
        }
    }

    /** Opens the sheet for the current [UpdateUiState.Available] (e.g. tapping the Info tile). */
    fun showSheet() {
        (_state.value as? UpdateUiState.Available)?.let { _prompt.value = it.update }
    }

    fun dismissPrompt() { _prompt.value = null }

    fun setChannel(channel: UpdateChannel) {
        if (channel == this.channel.value) return
        job?.cancel()
        _state.value = UpdateUiState.Idle
        viewModelScope.launch { settings.setUpdateChannel(channel) }
    }

    fun downloadAndInstall(update: UpdateCheckResult.Available) {
        _prompt.value = null
        viewModelScope.launch { installer.downloadAndInstall(update) }
    }

    companion object {
        /** Versions already prompted in this process (sheet at most once per version per process). */
        private val promptedVersions: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        val releasesUrl: String = "https://github.com/${BuildConfig.GITHUB_REPO}/releases"
    }
}

/** Activity-scoped [UpdateViewModel] so the Info tile and [UpdatePromptHost] share one state. */
@Composable
fun rememberUpdateViewModel(): UpdateViewModel {
    val client: GitHubReleasesClient = koinInject()
    val clock: Clock = koinInject()
    val settings: SettingsRepository = koinInject()
    val owner = LocalContext.current.findActivity()
    val factory = viewModelFactory {
        initializer {
            UpdateViewModel(
                client = client,
                clock = clock,
                settings = settings,
                installer = UpdateInstaller.Stub,
                versionName = BuildConfig.VERSION_NAME,
                autoCheckAllowed = !BuildConfig.DEBUG,
            )
        }
    }
    return if (owner != null) viewModel(viewModelStoreOwner = owner, factory = factory) else viewModel(factory = factory)
}

private tailrec fun android.content.Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
