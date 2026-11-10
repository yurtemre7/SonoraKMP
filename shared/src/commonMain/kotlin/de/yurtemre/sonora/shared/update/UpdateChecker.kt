package de.yurtemre.sonora.shared.update

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
    suspend fun check(includePreReleases: Boolean): UpdateCheckResult {
        if (!isOnline()) return UpdateCheckResult.Failed(UpdateFailure.NoNetwork)
        val local = SemVer.parseLocal(localVersionName)
            ?: return UpdateCheckResult.Failed(UpdateFailure.Unknown("Bad local version $localVersionName"))

        val candidate: Pair<Release, SemVer> = if (includePreReleases) {
            when (val r = client.list(20)) {
                is FetchResult.Err -> return UpdateCheckResult.Failed(r.reason)
                is FetchResult.Ok -> r.value.filter { !it.draft }
                    .mapNotNull { rel -> SemVer.parse(rel.tagName)?.let { rel to it } }
                    .maxByOrNull { it.second }
                    ?: return UpdateCheckResult.UpToDate
            }
        } else {
            when (val r = client.latest()) {
                is FetchResult.Err -> return UpdateCheckResult.Failed(r.reason)
                is FetchResult.Ok -> {
                    val v = SemVer.parse(r.value.tagName)
                        ?: return UpdateCheckResult.Failed(UpdateFailure.Malformed)
                    if (v.isPreRelease || r.value.prerelease) return UpdateCheckResult.UpToDate
                    r.value to v
                }
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

    companion object {
        const val THROTTLE_MS: Long = 6 * 60 * 60 * 1000L

        fun apkAssetName(version: SemVer): String = "sonora-$version.apk"

        /** Whether an automatic check may run now (6 h throttle + rate-limit reset). Manual checks ignore this. */
        fun shouldAutoCheck(nowMs: Long, lastCheckMs: Long, rateLimitResetMs: Long): Boolean =
            nowMs - lastCheckMs >= THROTTLE_MS && nowMs >= rateLimitResetMs
    }
}
