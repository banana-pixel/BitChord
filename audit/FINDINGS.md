# BitChord Findings

Audit artifacts referenced from AUDIT_REPORT.md.

## F-01 Updater lacks APK checksum verification (MEDIUM-LOW)
- `app/src/main/java/com/music/bitchord/data/AppUpdateChecker.kt`
- Polls `https://api.github.com/repos/kushagrasinghx/BitChord/releases/latest`, downloads the matching `BitChord-*.apk` asset, then hands it to the installer (`REQUEST_INSTALL_PACKAGES` + PackageInstaller/AppUpdateChecker flow). The downloaded bytes are compared against nothing; the platform's install-time signature-continuity check is the only guarantee.
- Impact: a compromised release asset (leaked token, infrastructure swap) would reach the install prompt; the user's guard is the signing key check, which fails only if the attacker cannot re-sign. No confidentiality impact from taking the download itself.
- Recommendation: publish a SHA-256 for each release asset and verify it before initiating install; fail closed with a visible error otherwise.

## F-02 TV LAN auth server is unauthenticated (MEDIUM)
- `tv/src/main/java/com/music/bitchord/ui/tv/auth/TvAuthServer.kt`
- The TV app runs an HTTP server on the LAN (port 8765, pinned else ephemeral) with `Access-Control-Allow-Origin: *`, no authentication, no rate limit, and no pairing token:
  - `GET /api/status`, `POST /api/action`, `POST /api/play` — full playback control without any proof the caller is the phone app;
  - `GET /submit` — the phone-side login flow posts the user's YouTube Music cookie here over plaintext LAN HTTP (the TV screen shows a code, but the server never verifies one).
- Impact: any device on the same LAN can control playback or inject submissions; a passive network observer captures the YouTube authorization cookie exchanged over plaintext HTTP.
- Recommendation: mint a one-time pairing token displayed on the TV, require it on every endpoint (signature or bearer), stop serving after the auth flow completes, and consider shortening the window further.

## F-03 JS string interpolation in QuickJS module calls (LOW-MEDIUM)
- `app/src/main/java/com/music/bitchord/data/sources/module/ModuleManager.kt:268,314` and `QuickJsExecutor.kt:262,282`
- Arguments are spliced unescaped into JavaScript source: `args = listOf("\"$query\"", ...)`, `"\"$trackId\""` and `contextArg(...)` which composes `{"$key":{"value":"$value"}}` from raw settings strings. A `"` (or a backslash) inside the search query, a track id, quality string or setting value breaks out of the JS string literal and yields attacker-controlled JS inside the sandboxed QuickJS VM.
- Impact boundary: the VM exposes only `console` and the `__spine.fetch` HTTP binding; no file, process, or Android APIs, separate OkHttp client without app cookies. No host escape observed in the sandbox setup; injected code is still restricted to module capabilities. Impact is confined to the module's own privileges (network calls with module-scoped first-party-looking context, "search result" fabrication etc.), and it would run in parallel with a legitimate module the user added.
- Recommendation: JSON-encode every interpolated argument (JSON.stringify semantics) before splicing into source, or pass them via an exposed bindings table instead of source-level interpolation.

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
- Many lyric/embed/direct-URL helpers ship as plain HTTPS with no auth (`lrclib.net`, `lrc.red`, `api-lyrics.simpmusic.org`, `lyrics-plus-backend.vercel.app`, `unison.boidu.dev`, `lyrics-api.binimum.org`, `lyrics-api.boidu.dev`, musixmatch scraping, kugou, gen­ius web scraping, Apple Music catalog, Discord avatar CDN). These are public/static-style endpoints; no fix.
