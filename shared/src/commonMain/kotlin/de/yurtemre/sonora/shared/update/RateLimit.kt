package de.yurtemre.sonora.shared.update

import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode

object RateLimit {
    /**
     * Returns the reset time in epoch ms if [status]/[headers] indicate a GitHub rate limit, else null.
     * 403/429 with `X-RateLimit-Remaining: 0` → `X-RateLimit-Reset` (epoch s); otherwise `Retry-After` (s); 429 alone → now + 60 s.
     */
    fun resetAtMs(status: HttpStatusCode, headers: Headers, nowMs: Long): Long? {
        if (status != HttpStatusCode.Forbidden && status != HttpStatusCode.TooManyRequests) return null
        val retryAfter = headers["Retry-After"]?.trim()?.toLongOrNull()
        if (headers["X-RateLimit-Remaining"]?.trim() == "0") {
            headers["X-RateLimit-Reset"]?.trim()?.toLongOrNull()?.let { return it * 1000 }
            return nowMs + (retryAfter ?: 60) * 1000
        }
        if (retryAfter != null) return nowMs + retryAfter * 1000
        return if (status == HttpStatusCode.TooManyRequests) nowMs + 60_000 else null
    }
}
