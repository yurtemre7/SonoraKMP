package de.yurtemre.sonora.shared.update

import de.yurtemre.sonora.shared.model.UpdateChannel
import de.yurtemre.sonora.shared.repo.api.Clock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private val now = 1_000_000_000L
    private val clock = Clock { now }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var lastPath = ""

    private fun rel(v: String, pre: Boolean = v.contains('-'), draft: Boolean = false, assets: Boolean = true) = """
        {"tag_name":"v$v","prerelease":$pre,"draft":$draft,"body":"## $v notes","html_url":"https://x",
         "assets":${if (assets) """[{"name":"sonora-$v.apk","browser_download_url":"https://d/$v.apk","size":10},
           {"name":"sonora-$v.apk.sha256","browser_download_url":"https://d/$v.sha","size":1}]""" else "[]"}}
    """.trimIndent()

    private fun checker(local: String, online: Boolean = true, handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): UpdateChecker {
        val engine = MockEngine { req -> lastPath = req.url.encodedPath + "?" + req.url.encodedQuery; assertEquals("Sonora-Android", req.headers[HttpHeaders.UserAgent]); handler(req) }
        return UpdateChecker(GitHubReleasesClient(HttpClient(engine), clock), clock, local) { online }
    }

    @Test fun newerAvailable() = runTest {
        val r = checker("1.28.1-dev") { respond(rel("1.29.0"), HttpStatusCode.OK, json) }.check(UpdateChannel.Stable)
        assertIs<UpdateCheckResult.Available>(r)
        assertEquals("sonora-1.29.0.apk", r.apkAsset.name)
        assertEquals("sonora-1.29.0.apk.sha256", r.checksumAsset.name)
        assertEquals("## 1.29.0 notes", r.notesMarkdown)
        assertTrue(lastPath.startsWith("/repos/yurtemre7/SonoraKMP/releases/latest"))
    }

    @Test fun olderOrEqualUpToDate() = runTest {
        assertEquals(UpdateCheckResult.UpToDate, checker("1.29.0") { respond(rel("1.28.0"), HttpStatusCode.OK, json) }.check(UpdateChannel.Stable))
        assertEquals(UpdateCheckResult.UpToDate, checker("1.29.0") { respond(rel("1.29.0"), HttpStatusCode.OK, json) }.check(UpdateChannel.Stable))
    }

    @Test fun prereleaseListMode() = runTest {
        // The rolling "nightly" pre-release (tag is not a version) must never be offered.
        val nightly = """{"tag_name":"nightly","prerelease":true,"draft":false,"body":"","html_url":"https://x",
            "assets":[{"name":"sonora-9.9.9-nightly.20991231.apk","browser_download_url":"https://d/n.apk","size":10}]}"""
        val body = "[$nightly,${rel("1.30.0-beta.2", draft = true)},${rel("1.30.0-beta.1")},${rel("1.29.0")}]"
        val r = checker("1.29.0") { respond(body, HttpStatusCode.OK, json) }.check(UpdateChannel.PreRelease)
        assertIs<UpdateCheckResult.Available>(r)
        assertEquals("1.30.0-beta.1", r.version.toString())
        assertTrue(lastPath.contains("/releases?") && lastPath.contains("per_page=20"))
    }

    private fun nightlyRel(v: String) = """{"tag_name":"nightly","prerelease":true,"draft":false,"body":"n","html_url":"https://x",
        "assets":[{"name":"sonora-$v.apk","browser_download_url":"https://d/n.apk","size":10},
        {"name":"sonora-$v.apk.sha256","browser_download_url":"https://d/n.sha","size":1}]}"""

    private fun nightlyChecker(local: String, stable: String, nightly: String?) = checker(local) { req ->
        if (req.url.encodedPath.endsWith("/releases/tags/nightly")) {
            if (nightly == null) respond("{}", HttpStatusCode.NotFound, json) else respond(nightlyRel(nightly), HttpStatusCode.OK, json)
        } else respond(rel(stable), HttpStatusCode.OK, json)
    }

    @Test fun nightlyNewerThanInstalledReleaseOffered() = runTest {
        val r = nightlyChecker("1.28.1", "1.28.1", "1.28.2-nightly.202611110317").check(UpdateChannel.Nightly)
        assertIs<UpdateCheckResult.Available>(r)
        assertEquals("sonora-1.28.2-nightly.202611110317.apk", r.apkAsset.name)
        // No nightly published (404) is not an error.
        assertEquals(UpdateCheckResult.UpToDate, nightlyChecker("1.28.1", "1.28.1", null).check(UpdateChannel.Nightly))
    }

    @Test fun newerNightlyOfferedOverOlderInstalledNightly() = runTest {
        val r = nightlyChecker("1.28.2-nightly.202611100317", "1.28.1", "1.28.2-nightly.202611110317").check(UpdateChannel.Nightly)
        assertIs<UpdateCheckResult.Available>(r)
        assertEquals("1.28.2-nightly.202611110317", r.version.toString())
    }

    @Test fun stableNewerThanInstalledNightlyWins() = runTest {
        val r = nightlyChecker("1.28.2-nightly.202611110317", "1.28.2", "1.28.2-nightly.202611110317").check(UpdateChannel.Nightly)
        assertIs<UpdateCheckResult.Available>(r)
        assertEquals("1.28.2", r.version.toString())
    }

    @Test fun preReleaseUserToggleOffGetsNextStable() = runTest {
        val r = checker("1.30.0-beta.1") { respond(rel("1.30.0"), HttpStatusCode.OK, json) }.check(UpdateChannel.Stable)
        assertIs<UpdateCheckResult.Available>(r)
    }

    @Test fun rateLimited403() = runTest {
        val r = checker("1.0.0") {
            respond("{}", HttpStatusCode.Forbidden, headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("2000000")))
        }.check(UpdateChannel.Stable)
        assertEquals(UpdateCheckResult.Failed(UpdateFailure.RateLimited(2_000_000_000L)), r)
    }

    @Test fun rateLimited429RetryAfter() = runTest {
        val r = checker("1.0.0") { respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "30")) }.check(UpdateChannel.Stable)
        assertEquals(UpdateCheckResult.Failed(UpdateFailure.RateLimited(now + 30_000)), r)
    }

    @Test fun serverError() = runTest {
        assertEquals(UpdateCheckResult.Failed(UpdateFailure.Server(500)),
            checker("1.0.0") { respond("", HttpStatusCode.InternalServerError) }.check(UpdateChannel.Stable))
    }

    @Test fun offline() = runTest {
        assertEquals(UpdateCheckResult.Failed(UpdateFailure.NoNetwork), checker("1.0.0") { throw IOException("down") }.check(UpdateChannel.Stable))
        assertEquals(UpdateCheckResult.Failed(UpdateFailure.NoNetwork), checker("1.0.0", online = false) { error("no call") }.check(UpdateChannel.Stable))
    }

    @Test fun missingAsset() = runTest {
        assertEquals(UpdateCheckResult.Failed(UpdateFailure.NoAsset),
            checker("1.0.0") { respond(rel("1.29.0", assets = false), HttpStatusCode.OK, json) }.check(UpdateChannel.Stable))
    }

    @Test fun malformedTag() = runTest {
        assertEquals(UpdateCheckResult.Failed(UpdateFailure.Malformed),
            checker("1.0.0") { respond("""{"tag_name":"nightly"}""", HttpStatusCode.OK, json) }.check(UpdateChannel.Stable))
    }

    @Test fun throttle() {
        val h6 = UpdateChecker.THROTTLE_MS
        assertTrue(UpdateChecker.shouldAutoCheck(now, now - h6, 0))
        assertFalse(UpdateChecker.shouldAutoCheck(now, now - h6 + 1, 0))
        assertFalse(UpdateChecker.shouldAutoCheck(now, 0, now + 1))
        assertTrue(UpdateChecker.shouldAutoCheck(now, 0, now))
    }
}
