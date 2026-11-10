package de.yurtemre.sonora.shared.update

import de.yurtemre.sonora.shared.repo.api.Clock
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface FetchResult<out T> {
    data class Ok<T>(val value: T) : FetchResult<T>
    data class Err(val reason: UpdateFailure) : FetchResult<Nothing>
}

/** Unauthenticated GitHub Releases API client (SPEC §7). */
class GitHubReleasesClient(
    private val http: HttpClient,
    private val clock: Clock,
    val repo: String = DEFAULT_REPO,
    private val baseUrl: String = "https://api.github.com",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun latest(): FetchResult<Release> =
        fetch("$baseUrl/repos/$repo/releases/latest") { json.decodeFromString<Release>(it) }

    suspend fun list(perPage: Int = 20): FetchResult<List<Release>> =
        fetch("$baseUrl/repos/$repo/releases", perPage) { json.decodeFromString<List<Release>>(it) }

    /** Fetches a small text asset (e.g. the `.sha256` file). */
    suspend fun fetchText(url: String): FetchResult<String> = fetch(url, accept = "application/octet-stream") { it }

    private suspend fun <T> fetch(
        url: String,
        perPage: Int? = null,
        accept: String = "application/vnd.github+json",
        decode: (String) -> T,
    ): FetchResult<T> = try {
        val resp: HttpResponse = http.get(url) {
            header(HttpHeaders.Accept, accept)
            header("X-GitHub-Api-Version", "2022-11-28")
            header(HttpHeaders.UserAgent, USER_AGENT)
            if (perPage != null) parameter("per_page", perPage)
        }
        val reset = RateLimit.resetAtMs(resp.status, resp.headers, clock.nowMs())
        when {
            reset != null -> FetchResult.Err(UpdateFailure.RateLimited(reset))
            !resp.status.isSuccess() -> FetchResult.Err(UpdateFailure.Server(resp.status.value))
            else -> try {
                FetchResult.Ok(decode(resp.bodyAsText()))
            } catch (e: SerializationException) {
                FetchResult.Err(UpdateFailure.Malformed)
            } catch (e: IllegalArgumentException) {
                FetchResult.Err(UpdateFailure.Malformed)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        FetchResult.Err(UpdateFailure.NoNetwork)
    } catch (e: Exception) {
        FetchResult.Err(UpdateFailure.Unknown(e.message))
    }

    companion object {
        const val DEFAULT_REPO = "yurtemre7/SonoraKMP"
        const val USER_AGENT = "Sonora-Android"
    }
}
