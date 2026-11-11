package de.yurtemre.sonora.update

import android.content.Context
import de.yurtemre.sonora.shared.update.UpdateCheckResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Real [UpdateInstaller]: Ktor download → SHA-256 → PackageInstaller session. */
class AndroidUpdateInstaller(
    context: Context,
    client: HttpClient,
    override val isSupported: Boolean,
) : UpdateInstaller {
    private val app = context.applicationContext
    private val downloader = ApkDownloader(client, File(app.cacheDir, "updates"))
    private val permission = InstallPermissionHelper(app)
    private val packageInstaller = PackageInstallerHelper(app)

    override fun canInstallPackages(): Boolean = permission.canRequestPackageInstalls()
    override fun openInstallPermissionSettings() = permission.openSettings()

    override fun download(update: UpdateCheckResult.Available): Flow<DownloadEvent> =
        downloader.download(update.apkAsset.downloadUrl, update.apkAsset.name, update.apkAsset.size)

    override suspend fun expectedSha256(update: UpdateCheckResult.Available): String? =
        Sha256Verifier.expected(downloader.fetchText(update.checksumAsset.downloadUrl), update.apkAsset.name)

    override suspend fun verify(file: File, expectedHex: String): Boolean =
        withContext(Dispatchers.IO) { Sha256Verifier.verify(file, expectedHex) }

    override suspend fun install(file: File): InstallResult = packageInstaller.install(file)
}
