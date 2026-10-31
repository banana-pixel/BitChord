package com.music.bitchord.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * BitChord ships as a sideloaded APK off GitHub Releases rather than through
 * a store, so there's nothing to push an update notice on its own — this
 * polls the repo's "latest release" once per launch and compares its tag
 * against the running build.
 *
 * The update itself is also handled here: the release's `.apk` asset is
 * downloaded into the app's cache and handed to the system package installer,
 * so the whole round trip stays inside the app instead of bouncing out to a
 * browser.
 */
object AppUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        /** The release's own Markdown body, shown as this update's "what's new". */
        val notes: String?,
        /**
         * SHA-256 of the APK bytes, as published next to the release
         * (a `SHA256SUMS.txt` asset). Null when the release does not publish a
         * usable one — in that case [apkUrl] is null too, because an
         * unverifiable APK is never offered for in-app installation.
         */
        val apkSha256: String? = null,
    )

    private const val CACHE_SUBDIR = "updates"

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/kushagrasinghx/BitChord/releases/latest"

    /** The release's `<prefix>SHA256SUMS.txt` asset holds digests for every APK of this flavour. */
    private const val SHA_SUMS_SUFFIX = "SHA256SUMS.txt"

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    /** Where this update's APK download currently stands, for the dialog's progress row. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    /** Set from the UI thread when the user cancels; polled between network reads. */
    @Volatile
    private var downloadCancelled = false

    suspend fun check() = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(LATEST_RELEASE_URL).build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            } ?: return@runCatching
            val release = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
            val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
            val apkUrl = apkAssetUrl(release)
            // A digest is required for every release, with no date- or
            // version-based exceptions. Anything that leaves us without one —
            // no manifest asset, a failed fetch, no entry for this file name,
            // conflicting entries, malformed metadata — withholds the in-app
            // install step, and the dialog falls back to the releases page.
            // Keying this on `published_at` was the earlier design and it was
            // wrong twice over: it let releases published during the grace
            // window skip verification entirely, and because GitHub keeps the
            // original date when a release is edited, an attacker with release
            // write access could retag and re-upload an old release to land on
            // the unverified path while still being "the latest".
            val apkSha256 = shaSumsUrl(release)?.let { sums -> fetchSumsEntry(sums, apkUrl) }
            val notes = release["body"]?.jsonPrimitive?.contentOrNull
            val latest = tag.removePrefix("v")
            if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                _available.value = UpdateInfo(
                    latest,
                    url,
                    installableApkUrl(apkUrl, apkSha256),
                    notes,
                    apkSha256,
                )
            }
        }
    }

    /**
     * Wipes any APK left over from a previous run. Called once at cold start
     * so a downloaded update is only ever "Install Now" for the session that
     * downloaded it — the next launch starts clean rather than trying to work
     * out whether a leftover file is still good.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * One release carries the phone app and the TV app, each as a universal
     * APK plus per-ABI splits:
     *
     *     BitChord-v1.8.apk     BitChord-v1.8_arm64-v8a.apk     ...
     *     BitChordTV-v1.8.apk   BitChordTV-v1.8_arm64-v8a.apk   ...
     *
     * so this app's APKs are the ones named with its own
     * [BuildConfig.UPDATE_APK_PREFIX] — "BitChord-" never matches a TV file,
     * since the prefix ends at the hyphen. The universal build (no `_ABI`
     * suffix) is taken first, as it installs anywhere; failing that, the split
     * for this device's ABI. A release with neither leaves
     * [UpdateInfo.apkUrl] null and the UI falls back to opening the releases
     * page.
     */
    internal fun apkAssetUrl(release: JsonObject): String? = runCatching {
        val apks = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val ours = name.startsWith(BuildConfig.UPDATE_APK_PREFIX, ignoreCase = true) &&
                    name.endsWith(".apk", ignoreCase = true) &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
                if (ours) name.removeSuffix(".apk").removeSuffix(".APK") to url else null
            }
            .orEmpty()
        apks.firstOrNull { (name, _) -> '_' !in name }?.second
            ?: Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { (name, _) -> name.endsWith("_$abi") }?.second
            }
    }.getOrNull()

    /**
     * The URL of this release's checksum manifest — the `SHA256SUMS.txt`
     * asset CI publishes next to the APKs — or null when the release does not
     * ship exactly one. Null means no in-app install (see [installableApkUrl]).
     */
    internal fun shaSumsUrl(release: JsonObject): String? = runCatching {
        val expected = "${BuildConfig.UPDATE_APK_PREFIX}$SHA_SUMS_SUFFIX"
        val urls = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull
                // Case-insensitive prefix keeps phone and TV manifests apart:
                // BitChord-SHA256SUMS.txt vs BitChordTV-SHA256SUMS.txt.
                val ours = name != null && name.equals(expected, ignoreCase = true) &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
                if (!ours) null else asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
            }
            .orEmpty()
            .toSet()
        // Exactly one manifest. Two assets claiming to be it — even if they
        // agree today — means the release metadata cannot be trusted to say
        // which one the digest came from, so refuse rather than pick.
        urls.singleOrNull()
    }.getOrNull()

    /**
     * The APK URL to offer for in-app installation, or null to fall back to
     * the releases page.
     *
     * One rule, no exceptions: a URL is only handed to the installer when a
     * digest was resolved for exactly that asset. Every way of failing to get
     * one — no manifest asset, two manifest assets, a failed fetch, no entry
     * for this file name, conflicting entries, malformed metadata — arrives
     * here as `digest == null` and withholds the download.
     */
    internal fun installableApkUrl(apkUrl: String?, digest: String?): String? =
        apkUrl?.takeIf { digest != null }

    /**
     * The digest line for [apkUrl] inside the manifest at [sumsUrl], lower
     * cased; null when the entry is missing, duplicated with inconsistent
     * hashes, or the manifest is malformed.
     */
    internal fun fetchSumsEntry(sumsUrl: String, apkUrl: String?): String? {
        if (apkUrl == null) return null
        return runCatching {
            val request = Request.Builder().url(sumsUrl).build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            } ?: return null
            parseSumsEntry(body, apkUrl.substringAfterLast('/'))
        }.getOrNull()
    }

    /**
     * Picks [fileName]'s digest out of a `sha256sum`-style manifest body.
     *
     * Accepted line shape, text and binary (` *`) marker style alike:
     *
     *     <64 hex chars><one space><optional '*'><file name>
     *
     * Blank and comment lines simply do not match; anything the pattern does
     * not anchor onto disqualifies that line. A file name appearing twice with
     * different digests means somebody edited the manifest — reject instead of
     * trusting a coin toss. CRLF manifests are handled by the pattern itself.
     */
    internal fun parseSumsEntry(body: String, fileName: String): String? {
        if (fileName.isEmpty()) return null
        val matches = SHA_LINE.findAll(body)
            .mapNotNull { m ->
                val hash = m.groupValues[1].lowercase()
                val name = m.groupValues[2].trim()
                if (name != fileName) null else hash
            }
            .toSet()
        return matches.singleOrNull()?.takeIf { isHexDigest64(it) }
    }

    // Anchored on line starts so a dotted 128-char joke hex string cannot let
    // an entry's tail be "parsed" as the digest for a name it does not own.
    // GNU coreutils emits "<hash>  <name>" in text mode and "<hash> *<name>"
    // with -b; the pattern accepts exactly one separator column, which makes
    // the space the format requires and the optional binary marker explicit.
    private val SHA_LINE = Regex("(?m)^\\s*([0-9a-fA-F]{64}) [ *]([^\\r\\n]+)$")

    private fun isHexDigest64(raw: String): Boolean = raw.length == 64 &&
        raw.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }

    /**
     * Streams the current update's APK into the app cache, reporting progress
     * through [download]. A finished file survives a cancelled dialog: until
     * the state is reset, "Install Now" comes straight back without a second
     * download.
     */
    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = _available.value ?: return@withContext
        val url = info.apkUrl ?: return@withContext
        downloadCancelled = false
        _download.value = DownloadState.Downloading(0f)

        runCatching {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            // Drop anything left over from an earlier attempt.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "bitchord-${info.version}.apk")

            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url(url).build()
            Http.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().digesting(digest).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            if (downloadCancelled) {
                                _download.value = DownloadState.Idle
                                return@withContext
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            total?.let {
                                _download.value =
                                    DownloadState.Downloading((readTotal.toFloat() / it).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            // The release manifest publishes what the APK's bytes should hash
            // to. A mismatch means the download was tampered with or truncated
            // somewhere between GitHub and here — discard the file and refuse
            // to offer Install Now rather than hand it to the installer.
            //
            // A missing digest is treated the same way. check() already
            // withholds the URL in that case, so reaching here without one
            // means the state was altered after the fact (or a caller skipped
            // the gate); either way there is nothing to verify against, and
            // unverified bytes do not go to the installer.
            val expected = info.apkSha256
            if (expected == null || !digestMatches(digest, expected)) {
                target.delete()
                _download.value = DownloadState.Failed(
                    if (expected == null) {
                        "Update APK has no published checksum; download discarded, nothing installed."
                    } else {
                        "Update APK failed its integrity check; download discarded, nothing installed."
                    },
                )
                return@withContext
            }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            _download.value = if (downloadCancelled) {
                DownloadState.Idle
            } else {
                DownloadState.Failed(error.message ?: "Download failed")
            }
        }
    }

    private fun digestMatches(digest: MessageDigest, expectedHex: String): Boolean {
        // Only SHA-256 manifests are trusted, so the expected value's format is
        // checked by fetchSumsEntry before it gets here.
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        // Constant-time handshake even though both sides are strings we built.
        return MessageDigest.isEqual(actual.toByteArray(), expectedHex.lowercase().toByteArray())
    }

    private fun InputStream.digesting(digest: MessageDigest): InputStream =
        DigestInputStream(this, digest)

    /** Stops an in-flight download; the next read loop sees this and bails. */
    fun cancelDownload() {
        downloadCancelled = true
    }

    /** Back to square one after a failure, so the dialog offers Download again. */
    fun resetDownload() {
        _download.value = DownloadState.Idle
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Sideloaded apps need the user's blessing per app ("install unknown apps");
     * without it the installer intent silently does nothing on most ROMs, so
     * the user is sent to that one switch first and taps Install again after.
     * Some TV builds have no such settings screen; there the installer is
     * launched anyway and shows its own "not allowed" prompt with a way in.
     */
    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (opened) return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
        )
    }

    /** A version split into its numeric dotted parts and whether it carries a "-suffix" (e.g. "-beta2"). */
    private data class ParsedVersion(val parts: List<Int>, val isPreRelease: Boolean)

    private fun parseVersion(raw: String): ParsedVersion {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return ParsedVersion(base.split(".").map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /**
     * Numeric, dot-separated comparison — "1.10" outranks "1.9" — with one
     * extra rule: a "-betaN" build (see the debug build type's
     * `versionNameSuffix` in app/build.gradle.kts) is treated as older than a
     * plain release of the same numbers, since the beta by definition predates
     * the tag it was testing toward. Without this, a beta and the release it
     * matches compare equal and testers never get nudged onto the real build.
     */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.isPreRelease && !l.isPreRelease
    }
}
