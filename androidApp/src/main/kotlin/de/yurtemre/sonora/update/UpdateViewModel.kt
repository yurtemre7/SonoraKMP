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
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File
import java.io.IOException

/** Download + install of an [UpdateCheckResult.Available] release (§7, UPD-08). */
interface UpdateInstaller {
    /** False in development builds (`.dev`, debug-signed): release APKs can't be installed over them. */
    val isSupported: Boolean
    fun canInstallPackages(): Boolean
    fun openInstallPermissionSettings()
    fun download(update: UpdateCheckResult.Available): Flow<DownloadEvent>
    /** Parsed digest of the `.sha256` asset, or null when it is missing/malformed. */
    suspend fun expectedSha256(update: UpdateCheckResult.Available): String?
    suspend fun verify(file: File, expectedHex: String): Boolean
    suspend fun install(file: File): InstallResult
}

/** Why a download/install attempt ended (§7). */
sealed interface InstallError {
    data object NoNetwork : InstallError
    data class Http(val code: Int) : InstallError
    data object NoAsset : InstallError
    data object ChecksumMismatch : InstallError
    data object Cancelled : InstallError
    data class InstallFailed(val result: InstallResult) : InstallError
}

/** UI state of the updater (§7 state machine). */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val update: UpdateCheckResult.Available) : UpdateUiState
    data class Failed(val reason: UpdateFailure) : UpdateUiState

    /** States of an install attempt for [update]. */
    sealed interface Install : UpdateUiState { val update: UpdateCheckResult.Available }
    data class NeedsPermission(override val update: UpdateCheckResult.Available) : Install
    data class Downloading(override val update: UpdateCheckResult.Available, val bytes: Long, val total: Long) : Install {
        val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }
    data class Verifying(override val update: UpdateCheckResult.Available) : Install
    data class ReadyToInstall(override val update: UpdateCheckResult.Available, val file: File) : Install
    data class Installing(override val update: UpdateCheckResult.Available) : Install
    data class Installed(override val update: UpdateCheckResult.Available) : Install
    data class InstallFailed(override val update: UpdateCheckResult.Available, val error: InstallError) : Install
}

class UpdateViewModel(
    client: GitHubReleasesClient,
    private val clock: Clock,
    private val settings: SettingsRepository,
    private val installer: UpdateInstaller,
    versionName: String,
    private val scheduler: UpdateScheduler,
) : ViewModel() {
    private val checker = UpdateChecker(client, clock, versionName)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** Release whose sheet is open; null when hidden. */
    private val _prompt = MutableStateFlow<UpdateCheckResult.Available?>(null)
    val prompt: StateFlow<UpdateCheckResult.Available?> = _prompt.asStateFlow()

    val channel: StateFlow<UpdateChannel> = settings.settings.map { it.updateChannel }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UpdateChannel.Stable)
    val lastCheckMs: StateFlow<Long> = settings.settings.map { it.lastUpdateCheckMs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val installSupported: Boolean get() = installer.isSupported

    private var job: Job? = null
    private var installJob: Job? = null

    private val installActive: Boolean get() = installJob?.isActive == true

    /** Settings › Info › Check for updates: always runs, ignores throttle (UPD-07). */
    fun checkManually() {
        if (installActive) return
        job?.cancel()
        job = viewModelScope.launch { _state.value = UpdateUiState.Checking; run(prompt = false) }
    }

    /** Cold-start check (UPD-05), see [UpdateScheduler]. */
    fun autoCheckIfDue() {
        if (job?.isActive == true || installActive) return
        job = viewModelScope.launch {
            if (scheduler.isAutoCheckDue()) run(prompt = true)
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
                if (prompt && scheduler.markPrompted(result.version.toString())) _prompt.value = result
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

    /** Opens the sheet for the known update (Info tile), including a running/failed install. */
    fun showSheet() {
        when (val s = _state.value) {
            is UpdateUiState.Available -> _prompt.value = s.update
            is UpdateUiState.Install -> _prompt.value = s.update
            else -> Unit
        }
    }

    /** Hides the sheet; a running download continues and stays visible in Settings › Info. */
    fun dismissPrompt() {
        _prompt.value = null
        if (_state.value is UpdateUiState.NeedsPermission) _state.value = UpdateUiState.Available((_state.value as UpdateUiState.Install).update)
    }

    fun setChannel(channel: UpdateChannel) {
        if (channel == this.channel.value || installActive) return
        job?.cancel()
        _state.value = UpdateUiState.Idle
        viewModelScope.launch { settings.setUpdateChannel(channel) }
    }

    /** Download (after the unknown-sources permission), verify and install [update]. */
    fun downloadAndInstall(update: UpdateCheckResult.Available) {
        if (!installer.isSupported || installActive) return
        _prompt.value = update
        if (!installer.canInstallPackages()) {
            _state.value = UpdateUiState.NeedsPermission(update)
            installer.openInstallPermissionSettings()
            return
        }
        start(update)
    }

    fun openPermissionSettings() = installer.openInstallPermissionSettings()

    /** Activity resumed: continue once "install unknown apps" was granted in Settings. */
    fun onResume() {
        val s = _state.value
        if (s is UpdateUiState.NeedsPermission && installer.canInstallPackages()) start(s.update)
    }

    fun cancelDownload() {
        val s = _state.value as? UpdateUiState.Install ?: return
        if (s !is UpdateUiState.Downloading && s !is UpdateUiState.Verifying) return
        installJob?.cancel()
        installJob = null
        _state.value = UpdateUiState.InstallFailed(s.update, InstallError.Cancelled)
    }

    fun retry() {
        val s = _state.value as? UpdateUiState.InstallFailed ?: return
        downloadAndInstall(s.update)
    }

    private fun start(update: UpdateCheckResult.Available) {
        installJob = viewModelScope.launch {
            _state.value = try {
                attempt(update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadHttpException) {
                UpdateUiState.InstallFailed(update, InstallError.Http(e.code))
            } catch (_: IOException) {
                UpdateUiState.InstallFailed(update, InstallError.NoNetwork)
            }
        }
    }

    private suspend fun attempt(update: UpdateCheckResult.Available): UpdateUiState {
        _state.value = UpdateUiState.Downloading(update, 0, update.apkAsset.size)
        var file: File? = null
        installer.download(update).collect { e ->
            when (e) {
                is DownloadEvent.Progress -> _state.value = UpdateUiState.Downloading(update, e.bytes, e.total)
                is DownloadEvent.Finished -> file = e.file
            }
        }
        val apk = file ?: return UpdateUiState.InstallFailed(update, InstallError.NoNetwork)
        _state.value = UpdateUiState.Verifying(update)
        val expected = installer.expectedSha256(update)
            ?: return UpdateUiState.InstallFailed(update, InstallError.NoAsset)
        if (!installer.verify(apk, expected)) return UpdateUiState.InstallFailed(update, InstallError.ChecksumMismatch)
        _state.value = UpdateUiState.ReadyToInstall(update, apk)
        _state.value = UpdateUiState.Installing(update)
        return when (val r = installer.install(apk)) {
            InstallResult.Success -> UpdateUiState.Installed(update)
            InstallResult.Cancelled -> UpdateUiState.InstallFailed(update, InstallError.Cancelled)
            else -> UpdateUiState.InstallFailed(update, InstallError.InstallFailed(r))
        }
    }

    companion object {
        val releasesUrl: String = "https://github.com/${BuildConfig.GITHUB_REPO}/releases"

        /** Debug builds use `.dev` + the debug key: installing a release APK over them would fail (§7). */
        val installAllowedInBuild: Boolean = !BuildConfig.DEBUG && !BuildConfig.APPLICATION_ID.endsWith(".dev")
    }
}

/** Activity-scoped [UpdateViewModel] so the Info tile and [UpdatePromptHost] share one state. */
@Composable
fun rememberUpdateViewModel(): UpdateViewModel {
    val client: GitHubReleasesClient = koinInject()
    val http: HttpClient = koinInject()
    val clock: Clock = koinInject()
    val settings: SettingsRepository = koinInject()
    val context = LocalContext.current
    val owner = context.findActivity()
    val factory = viewModelFactory {
        initializer {
            UpdateViewModel(
                client = client,
                clock = clock,
                settings = settings,
                installer = AndroidUpdateInstaller(context, http, UpdateViewModel.installAllowedInBuild),
                versionName = BuildConfig.VERSION_NAME,
                scheduler = UpdateScheduler(settings, clock, autoCheckAllowed = !BuildConfig.DEBUG),
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
