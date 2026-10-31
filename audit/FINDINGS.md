# BitChord Findings

Audit artifacts referenced from AUDIT_REPORT.md.

## F-01 Updater lacks APK checksum verification (MEDIUM-LOW) — fixed and verified
- `app/src/main/java/com/music/bitchord/data/AppUpdateChecker.kt`
- Polls `https://api.github.com/repos/kushagrasinghx/BitChord/releases/latest`, downloads the matching `BitChord-*.apk` asset, then hands it to the installer (`REQUEST_INSTALL_PACKAGES` + PackageInstaller/AppUpdateChecker flow). The downloaded bytes were compared against nothing; the platform's install-time signature-continuity check was the only guarantee.
- Impact: a compromised release asset (leaked token, infrastructure swap) would reach the install prompt; the user's guard is the signing key check, which fails only if the attacker cannot re-sign. No confidentiality impact from taking the download itself.
- **Fix on branch (final form):** an APK is offered for in-app installation only when a SHA-256 was resolved for exactly that asset. `installableApkUrl(apkUrl, digest)` is the single decision point and returns null unless both are present. Failures that all land on that null: no `BitChord-SHA256SUMS.txt` asset, the asset not in `uploaded` state, more than one asset claiming that name, a non-2xx response, an unreachable host, a body with no entry for this file name, conflicting entries for it, a malformed/truncated digest, or an unparseable release payload. `downloadApk()` re-checks the digest it streams and deletes the file on mismatch; a null digest at that point also deletes and fails rather than installing unverified bytes. CI publishes the manifest next to the APKs (`.github/workflows/android.yml`).
- **Removed design:** an earlier revision gated the requirement on `published_at >= 2026-12-01`. That was wrong in two ways: releases published before the cutoff skipped verification entirely (a month-long hole from the audit date), and GitHub keeps the original `published_at` when a release is edited, so an attacker with release write access could retag and re-upload an old release and have it treated as legacy. There is no date, version or tag exception now.
- **Checksum verification is not authenticity.** A digest in the same release as the APK proves the bytes match what that release says they should be — it catches truncation, corruption and a single-asset swap. It does **not** prove who published them: an attacker who can write to the release can publish a matching manifest. Closing that requires a detached signature over the manifest with a key held outside CI, verified against a public key compiled into the app (FINAL_VERIFICATION.md §8.1). That is deliberately out of scope here.

## F-02 TV LAN auth server is unauthenticated (MEDIUM) — fixed and verified
- `tv/src/main/java/com/music/bitchord/ui/tv/auth/TvAuthServer.kt`
- The TV app ran an HTTP server on the LAN (port 8765, pinned else ephemeral) with `Access-Control-Allow-Origin: *`, no authentication, no rate limit, and no pairing token:
  - `GET /api/status`, `POST /api/action`, `POST /api/play` — full playback control without any proof the caller is the phone app;
  - `GET /submit` — the phone-side login flow posts the user's YouTube Music cookie here over plaintext LAN HTTP (the TV screen showed a code, but the server never verified one).
- Impact: any device on the same LAN could control playback or inject submissions; a passive network observer captured the YouTube authorization cookie exchanged over plaintext HTTP.
- **Fix on branch:** every `/api/*` call and `/remote` requires the `bitchord_tv` session cookie — 128 bits from `SecureRandom`, minted only after a successful pairing, `HttpOnly; SameSite=Lax`, compared with `MessageDigest.isEqual`. `/submit` and the landing page require a live pairing code (10-minute window, rotated on expiry and on use, 30 misses/5 min → 15-min per-IP ban). The `*` CORS header is gone, so other pages on the phone cannot read responses. The remote page no longer embeds the code.
- **Residual:** `/submit` still carries the YouTube cookie over plaintext LAN HTTP; a passive observer during the pairing moment can read it. Fixing that needs TLS the phone trusts, or a flow where the cookie never leaves the phone (FINAL_VERIFICATION.md §3.3, §8.2).

## F-03 JS string interpolation in QuickJS module calls (LOW-MEDIUM) — fixed and verified
- `app/src/main/java/com/music/bitchord/data/sources/module/ModuleManager.kt:268,314` and `QuickJsExecutor.kt:262,282`
- Arguments were spliced unescaped into JavaScript source: `args = listOf("\"$query\"", ...)`, `"\"$trackId\""` and `contextArg(...)` composed `{"$key":{"value":"$value"}}` from raw settings strings. A `"` (or a backslash) inside the search query, a track id, quality string or setting value broke out of the JS string literal and yielded attacker-controlled JS inside the sandboxed QuickJS VM.
- Impact boundary: the VM exposes only `console` and the `__spine.fetch` HTTP binding; no file, process, or Android APIs, separate OkHttp client without app cookies. No host escape observed in the sandbox setup; injected code was still restricted to module capabilities. Impact confined to the module's own privileges (network calls with module-scoped first-party-looking context, "search result" fabrication etc.), running in parallel with a legitimate module the user added.
- **Fix on branch:** `jsStringLiteral` escapes `\ " \n \r \t \b \f`, all other C0 controls as `\uXXXX`, and U+2028/U+2029; every argument to `searchTracks`/`getTrackStreamUrl` (including keys and values in `contextArg`) goes through it. `JsStringLiteralTest` round-trips each escape through a JS-equivalent decoder.

## F-04 Module assets lack integrity verification (LOW)
- Homepage: module JS download path (ModuleManager.loadModule) blindly trusts bytes over HTTPS; no checksum/signature. The source index is user-configured (this build ships no default module index URL), and the "app gallery" scheme is used by a user add-module flow — the trust level is explicit user choice.
- Also: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnerTubeXResolver.kt` fetches runtime config from `https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json` (third-party master branch, no pinning) to feed the JitPack `com.metrolist.innertubex` cipher logic. Risk of a compromised upstream publishing altered configs that change how the app builds/loads YouTube player logic. No exec observed: these are data files (JSON); preprocessed JS may be included per the provider scheme — flagged for supply-chain review; recommendation: pin a digest of the config file and refresh deliberately.

## F-05 Presence beacon has no opt-out (LOW)
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/presence/{Presence,AndroidPresence}.kt`
- Installs itself at app start (BitChordApplication → AndroidPresence.install()); periodically POSTs an install-scoped random UUID, platform name and playback-state flags to `https://api.bitchord.kushagrasingh.in/api/presence` — telemetry-style traffic with no settings toggle.
- Recommendation: add a settings toggle (off by default is a product decision; at minimum allow people to silence the heartbeat).

## F-06 ADB private key stored unencrypted (LOW-MEDIUM)
- `app/src/main/java/com/music/bitchord/wear/WatchInstaller.kt`
- Phone-side ADB client (JitPack `com.github.MuntashirAkon:libadb-android`) persists its RSA private key (PKCS#8 DER) in `noBackupFilesDir` as plain bytes. Local attacker with file access reads it and gets ADB shell access on the watch it was paired with.
- Recommendation: hold the key behind `EncryptedSharedPreferences`/MasterKey, or prompt/regenerate per pairing.

## F-07 Gradle wrapper provenance hardening (FIXED on branch)
- Baseline `gradle/wrapper/gradle-wrapper.jar` sha256 `e996d452d2645e70c01c11143ca2d3742734a28da2bf61f25c82bdc288c9e637` matched no jar built at official Gradle tags used (v8.6 era onward); class inspection found no exec/ProcessBuilder/new URL network activity, strings/classes match upstream Gradle-8.x-era wrappers. However the jar could not be tied to an official distribution hash.
- Repair on branch: replaced `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`, `gradle-wrapper.properties` with the exact set produced by the checksum-verified Gradle 8.11.1 distribution (jar sha256 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`), and added `distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6` to pin the distribution zip for all users.

## F-08 Spotify Web Player JS parsing (INFO)
- Spotify token/canvas fetching extracts tokens by regex at runtime from `open.spotifycdn.com/cdn/build/web-player/<version>.js`. HTTPS first-party spotifycdn target only; regex extraction/parsing without script execution. Logged, no fix required.

## F-09 EncryptedPrefs plaintext fallback (LOW)
- Auth storage prefers EncryptedSharedPreferences/Tink and silently falls back to plain SharedPreferences if the KeyStore is broken or bound out. Device-local plaintext with no malicious feature; better to fail closed or create a warn event, but low severity overall.

## F-10 Exported Wear receiver (INFO)
- `app/src/main/AndroidManifest.xml` exports a Wear MESSAGE_RECEIVED receiver (GMS host route). Standard `com.google.android.gms.wearable` DATA/MESSAGE dispatch; content paths are inside the app's own handling. No change.

## F-11 Disparate third-party services (INFO)
- Many lyric/embed/direct-URL helpers ship as plain HTTPS with no auth (`lrclib.net`, `lrc.red`, `api-lyrics.simpmusic.org`, `lyrics-plus-backend.vercel.app`, `unison.boidu.dev`, `lyrics-api.binimum.org`, `lyrics-api.boidu.dev`, musixmatch scraping, kugou, genius web scraping, Apple Music catalog, Discord avatar CDN). These are public/static-style endpoints; no fix.
