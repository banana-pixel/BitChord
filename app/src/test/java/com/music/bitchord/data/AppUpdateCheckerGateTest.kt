package com.music.bitchord.data

import com.music.bitchord.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The rule this class pins: an APK is offered for in-app installation only
 * when a SHA-256 was resolved for exactly that asset. There is no release age,
 * version or date that relaxes it — a missing or untrustworthy digest means
 * the user gets the releases page instead of a one-tap install.
 */
class AppUpdateCheckerGateTest {

    private val hash = "a".repeat(64)
    private val apk = "BitChord-v1.8.1.apk"
    private val apkUrl = "https://example.test/download/$apk"

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── The gate itself ───────────────────────────────────────────────────

    @Test
    fun `a digest for the asset opens the install path`() {
        assertEquals(apkUrl, AppUpdateChecker.installableApkUrl(apkUrl, hash))
    }

    @Test
    fun `no digest withholds the install path`() {
        assertNull(AppUpdateChecker.installableApkUrl(apkUrl, null))
    }

    @Test
    fun `no apk withholds the install path even with a digest`() {
        assertNull(AppUpdateChecker.installableApkUrl(null, hash))
    }

    @Test
    fun `neither present is still withheld`() {
        assertNull(AppUpdateChecker.installableApkUrl(null, null))
    }

    // ── Manifest selection failures ───────────────────────────────────────

    @Test
    fun `no manifest asset means no digest and no install`() {
        val release = releaseOf(asset(apk, apkUrl, "uploaded"))
        val digest = AppUpdateChecker.shaSumsUrl(release)?.let {
            AppUpdateChecker.fetchSumsEntry(it, apkUrl)
        }
        assertNull(digest)
        assertNull(AppUpdateChecker.installableApkUrl(apkUrl, digest))
    }

    @Test
    fun `a manifest asset that is not uploaded yet is not used`() {
        val release = releaseOf(
            asset(apk, apkUrl, "uploaded"),
            asset("${BuildConfig.UPDATE_APK_PREFIX}SHA256SUMS.txt", server.url("/sums").toString(), "new"),
        )
        assertNull(AppUpdateChecker.shaSumsUrl(release))
    }

    @Test
    fun `two manifest assets are refused rather than one being picked`() {
        val release = releaseOf(
            asset(apk, apkUrl, "uploaded"),
            asset("${BuildConfig.UPDATE_APK_PREFIX}SHA256SUMS.txt", server.url("/a").toString(), "uploaded"),
            asset("${BuildConfig.UPDATE_APK_PREFIX}sha256sums.TXT", server.url("/b").toString(), "uploaded"),
        )
        assertNull(AppUpdateChecker.shaSumsUrl(release))
    }

    @Test
    fun `a TV manifest never satisfies the phone app`() {
        val release = releaseOf(
            asset(apk, apkUrl, "uploaded"),
            asset("BitChordTV-SHA256SUMS.txt", server.url("/tv").toString(), "uploaded"),
        )
        assertNull(AppUpdateChecker.shaSumsUrl(release))
    }

    // ── Fetch failures ────────────────────────────────────────────────────

    @Test
    fun `an HTTP error from the manifest host yields no digest`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(AppUpdateChecker.fetchSumsEntry(server.url("/sums").toString(), apkUrl))
    }

    @Test
    fun `a 404 on the manifest yields no digest`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(AppUpdateChecker.fetchSumsEntry(server.url("/sums").toString(), apkUrl))
    }

    @Test
    fun `an empty manifest yields no digest`() {
        server.enqueue(MockResponse().setBody(""))
        assertNull(AppUpdateChecker.fetchSumsEntry(server.url("/sums").toString(), apkUrl))
    }

    @Test
    fun `a manifest without an entry for this APK yields no digest`() {
        server.enqueue(MockResponse().setBody("$hash  BitChord-v1.8.0.apk\n"))
        assertNull(AppUpdateChecker.fetchSumsEntry(server.url("/sums").toString(), apkUrl))
    }

    @Test
    fun `a manifest with conflicting entries for this APK yields no digest`() {
        server.enqueue(MockResponse().setBody("$hash  $apk\n${"b".repeat(64)}  $apk\n"))
        assertNull(AppUpdateChecker.fetchSumsEntry(server.url("/sums").toString(), apkUrl))
    }

    @Test
    fun `a malformed manifest body yields no digest`() {
        server.enqueue(MockResponse().setBody("not a checksum manifest at all\n"))
        assertNull(AppUpdateChecker.fetchSumsEntry(server.url("/sums").toString(), apkUrl))
    }

    @Test
    fun `a truncated digest yields no digest`() {
        server.enqueue(MockResponse().setBody("${"a".repeat(40)}  $apk\n"))
        assertNull(AppUpdateChecker.fetchSumsEntry(server.url("/sums").toString(), apkUrl))
    }

    @Test
    fun `a null apk url never triggers a manifest read`() {
        assertNull(AppUpdateChecker.fetchSumsEntry(server.url("/sums").toString(), null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a valid manifest returns the digest for the gate`() {
        server.enqueue(MockResponse().setBody("$hash  $apk\n"))
        val digest = AppUpdateChecker.fetchSumsEntry(server.url("/sums").toString(), apkUrl)
        assertEquals(hash, digest)
        assertEquals(apkUrl, AppUpdateChecker.installableApkUrl(apkUrl, digest))
    }

    @Test
    fun `the manifest request is a plain GET of the asset URL`() {
        server.enqueue(MockResponse().setBody("$hash  $apk\n"))
        val url = server.url("/sums").toString()
        AppUpdateChecker.fetchSumsEntry(url, apkUrl)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/sums", request.path)
    }

    @Test
    fun `an unreachable manifest host yields no digest instead of throwing`() {
        val url = server.url("/sums").toString()
        server.shutdown()
        assertNull(AppUpdateChecker.fetchSumsEntry(url, apkUrl))
        server = MockWebServer().also { it.start() }
    }

    // ── Full decision path over a live release payload ────────────────────

    @Test
    fun `a release without a manifest resolves to the releases page`() {
        val release = releaseOf(asset(apk, apkUrl, "uploaded"))
        assertNull(resolve(release))
    }

    @Test
    fun `a release with a matching manifest resolves to the download`() {
        server.enqueue(MockResponse().setBody("$hash  $apk\n"))
        val release = releaseOf(
            asset(apk, apkUrl, "uploaded"),
            asset("${BuildConfig.UPDATE_APK_PREFIX}SHA256SUMS.txt", server.url("/sums").toString(), "uploaded"),
        )
        assertEquals(apkUrl, resolve(release))
    }

    @Test
    fun `an old release without a manifest is not grandfathered in`() {
        // The removed design keyed the requirement on published_at, so a
        // release dated before the cutoff skipped verification entirely. The
        // date is now irrelevant: same payload, no manifest, no install.
        val release = releaseOf(asset(apk, apkUrl, "uploaded"), publishedAt = "2025-01-01T00:00:00Z")
        assertNull(resolve(release))
    }

    @Test
    fun `a future-dated release without a manifest is not installable`() {
        val release = releaseOf(asset(apk, apkUrl, "uploaded"), publishedAt = "2030-01-01T00:00:00Z")
        assertNull(resolve(release))
    }

    @Test
    fun `malformed published_at cannot be used to dodge verification`() {
        val release = releaseOf(asset(apk, apkUrl, "uploaded"), publishedAt = "not-a-date")
        assertNull(resolve(release))
    }

    @Test
    fun `an edited old release re-uploaded as latest still needs a digest`() {
        // The retag-and-swap path: an old release keeps its published_at when
        // it is edited and re-uploaded, so date-keyed logic would have treated
        // it as legacy. Nothing about it is special now.
        val release = releaseOf(
            asset("BitChord-v9.9.9.apk", "https://example.test/evil.apk", "uploaded"),
            publishedAt = "2026-10-19T00:00:00Z",
            tag = "v9.9.9",
        )
        assertNull(resolve(release))
    }

    @Test
    fun `a manifest serving a digest for another file does not open the gate`() {
        server.enqueue(MockResponse().setBody("$hash  BitChordTV-v1.8.1.apk\n"))
        val release = releaseOf(
            asset(apk, apkUrl, "uploaded"),
            asset("${BuildConfig.UPDATE_APK_PREFIX}SHA256SUMS.txt", server.url("/sums").toString(), "uploaded"),
        )
        assertNull(resolve(release))
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** The same two steps check() performs, minus the version comparison. */
    private fun resolve(release: JsonObject): String? {
        val url = AppUpdateChecker.apkAssetUrl(release)
        val digest = AppUpdateChecker.shaSumsUrl(release)?.let {
            AppUpdateChecker.fetchSumsEntry(it, url)
        }
        return AppUpdateChecker.installableApkUrl(url, digest)
    }

    private fun asset(name: String, url: String, state: String) = """
        {"name":"$name","browser_download_url":"$url","state":"$state"}
    """.trimIndent()

    /** A release payload with the given assets and optional metadata fields. */
    private fun releaseOf(
        vararg assets: String,
        publishedAt: String? = null,
        tag: String? = null,
    ): JsonObject = buildString {
        append("{")
        tag?.let { append("\"tag_name\":\"$it\",") }
        publishedAt?.let { append("\"published_at\":\"$it\",") }
        append("\"assets\":[")
        append(assets.joinToString(","))
        append("]}")
    }.let { Json.parseToJsonElement(it) as JsonObject }
}
