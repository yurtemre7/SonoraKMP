package de.yurtemre.sonora.shared.update

import de.yurtemre.sonora.shared.model.UpdateChannel
import de.yurtemre.sonora.shared.repo.api.Clock

sealed interface UpdateFailure {
    data object NoNetwork : UpdateFailure
    data class RateLimited(val resetAtMs: Long) : UpdateFailure
    data class Server(val code: Int) : UpdateFailure
    data object NoAsset : UpdateFailure
    data object Malformed : UpdateFailure
    data class Unknown(val message: String? = null) : UpdateFailure
}

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(
        val release: Release,
        val version: SemVer,
        val apkAsset: ReleaseAsset,
        val checksumAsset: ReleaseAsset,
        val notesMarkdown: String,
    ) : UpdateCheckResult
    data class Failed(val reason: UpdateFailure) : UpdateCheckResult
}

/** Checks GitHub Releases for a newer version than [localVersionName] (SPEC §7). No "skip version" (D-51). */
class UpdateChecker(
    private val client: GitHubReleasesClient,
    private val clock: Clock,
    private val localVersionName: String,
    /** Optional connectivity probe; returns false when there is no validated network. */
    private val isOnline: () -> Boolean = { true },
) {
    suspend fun check(channel: UpdateChannel): UpdateCheckResult {
        if (!isOnline()) return UpdateCheckResult.Failed(UpdateFailure.NoNetwork)
        val local = SemVer.parseLocal(localVersionName)
            ?: return UpdateCheckResult.Failed(UpdateFailure.Unknown("Bad local version $localVersionName"))

        val candidate: Pair<Release, SemVer> = when (channel) {
            UpdateChannel.PreRelease -> when (val r = client.list(20)) {
                is FetchResult.Err -> return UpdateCheckResult.Failed(r.reason)
                is FetchResult.Ok -> r.value.filter { !it.draft }
                    .mapNotNull { rel -> SemVer.parse(rel.tagName)?.let { rel to it } }
                    .maxByOrNull { it.second }
                    ?: return UpdateCheckResult.UpToDate
            }
            UpdateChannel.Stable -> when (val r = stableCandidate()) {
                is FetchResult.Err -> return UpdateCheckResult.Failed(r.reason)
                is FetchResult.Ok -> r.value ?: return UpdateCheckResult.UpToDate
            }
            UpdateChannel.Nightly -> {
                val stable = when (val r = stableCandidate()) {
                    is FetchResult.Err -> return UpdateCheckResult.Failed(r.reason)
                    is FetchResult.Ok -> r.value
                }
                val nightly = when (val r = client.nightly()) {
                    is FetchResult.Ok -> nightlyVersion(r.value)?.let { r.value to it }
                    is FetchResult.Err ->
                        if (r.reason == UpdateFailure.Server(404)) null else return UpdateCheckResult.Failed(r.reason)
                }
                listOfNotNull(stable, nightly).maxByOrNull { it.second } ?: return UpdateCheckResult.UpToDate
            }
        }
        val (release, remote) = candidate
        if (remote <= local) return UpdateCheckResult.UpToDate
        val apkName = apkAssetName(remote)
        val apk = release.assets.firstOrNull { it.name == apkName }
        val sha = release.assets.firstOrNull { it.name == "$apkName.sha256" }
        if (apk == null || sha == null) return UpdateCheckResult.Failed(UpdateFailure.NoAsset)
        return UpdateCheckResult.Available(release, remote, apk, sha, release.body.orEmpty())
    }

    /** `/releases/latest`: Ok(null) when it is not a stable release. */
    private suspend fun stableCandidate(): FetchResult<Pair<Release, SemVer>?> = when (val r = client.latest()) {
        // 404: the repository has no stable release yet.
        is FetchResult.Err -> if (r.reason == UpdateFailure.Server(404)) FetchResult.Ok(null) else r
        is FetchResult.Ok -> {
            val v = SemVer.parse(r.value.tagName)
            when {
                v == null -> FetchResult.Err(UpdateFailure.Malformed)
                v.isPreRelease || r.value.prerelease -> FetchResult.Ok(null)
                else -> FetchResult.Ok(r.value to v)
            }
        }
    }

    /** Version of the rolling nightly from its `sonora-<version>.apk` asset (needs the `.sha256` too). */
    private fun nightlyVersion(release: Release): SemVer? {
        val names = release.assets.map { it.name }.toSet()
        return names.asSequence()
            .filter { it.startsWith("sonora-") && it.endsWith(".apk") && "$it.sha256" in names }
            .mapNotNull { SemVer.parse(it.removePrefix("sonora-").removeSuffix(".apk")) }
            .maxOrNull()
    }

    companion object {
        const val THROTTLE_MS: Long = 6 * 60 * 60 * 1000L

        fun apkAssetName(version: SemVer): String = "sonora-$version.apk"

        /** Whether an automatic check may run now (6 h throttle + rate-limit reset). Manual checks ignore this. */
        fun shouldAutoCheck(nowMs: Long, lastCheckMs: Long, rateLimitResetMs: Long): Boolean =
            nowMs - lastCheckMs >= THROTTLE_MS && nowMs >= rateLimitResetMs
    }
}
