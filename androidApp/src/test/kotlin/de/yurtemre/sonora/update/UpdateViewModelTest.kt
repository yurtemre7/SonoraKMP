package de.yurtemre.sonora.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.shared.update.GitHubReleasesClient
import de.yurtemre.sonora.shared.update.Release
import de.yurtemre.sonora.shared.update.ReleaseAsset
import de.yurtemre.sonora.shared.update.SemVer
import de.yurtemre.sonora.shared.update.UpdateCheckResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dir: File = Files.createTempDirectory("upd").toFile()

    private val update = UpdateCheckResult.Available(
        release = Release(tagName = "v2.0.0"),
        version = SemVer.parse("2.0.0")!!,
        apkAsset = ReleaseAsset("sonora-2.0.0.apk", "https://x/apk", 100),
        checksumAsset = ReleaseAsset("sonora-2.0.0.apk.sha256", "https://x/sha"),
        notesMarkdown = "",
    )

    private class FakeInstaller(private val dir: File, private val verifyOk: Boolean) : UpdateInstaller {
        val gate = CompletableDeferred<Unit>()
        var installed = false
        override val isSupported = true
        override fun canInstallPackages() = true
        override fun openInstallPermissionSettings() = Unit
        override fun download(update: UpdateCheckResult.Available): Flow<DownloadEvent> = flow {
            emit(DownloadEvent.Progress(50, 100))
            gate.await()
            emit(DownloadEvent.Progress(100, 100))
            emit(DownloadEvent.Finished(File(dir, "a.apk").apply { writeText("x") }))
        }
        override suspend fun expectedSha256(update: UpdateCheckResult.Available) = "0".repeat(64)
        override suspend fun verify(file: File, expectedHex: String) = verifyOk.also { if (!it) file.delete() }
        override suspend fun install(file: File): InstallResult { installed = true; return InstallResult.Success }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() { Dispatchers.resetMain(); dir.deleteRecursively() }

    private fun TestScope.vm(installer: UpdateInstaller): UpdateViewModel {
        val clock = Clock { 0L }
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "s.preferences_pb") },
        )
        val client = GitHubReleasesClient(HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), clock)
        return UpdateViewModel(client, clock, settings, installer, "1.0.0", UpdateScheduler(settings, clock, false))
    }

    @Test fun cancelDuringDownload() = runTest(dispatcher) {
        val installer = FakeInstaller(dir, verifyOk = true)
        val vm = vm(installer)
        vm.downloadAndInstall(update)
        advanceUntilIdle()
        val s = vm.state.value
        assertIs<UpdateUiState.Downloading>(s)
        assertEquals(50, s.bytes)
        vm.cancelDownload()
        installer.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(UpdateUiState.InstallFailed(update, InstallError.Cancelled), vm.state.value)
        assertTrue(!installer.installed)
    }

    @Test fun checksumMismatchFails() = runTest(dispatcher) {
        val installer = FakeInstaller(dir, verifyOk = false).apply { gate.complete(Unit) }
        val vm = vm(installer)
        vm.downloadAndInstall(update)
        advanceUntilIdle()
        assertEquals(UpdateUiState.InstallFailed(update, InstallError.ChecksumMismatch), vm.state.value)
        assertTrue(!installer.installed && !File(dir, "a.apk").exists())
    }
}
