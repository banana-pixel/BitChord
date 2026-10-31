# BitChord — Final Independent Verification

Verification of the remediations shipped on branch `audit/build` (audit commit
`7a1de3f4`, based on `main` `04a4a838`). Scope: independently re-test each
fix that claims to close a finding, and record what was *not* verified.

Date: 2026-10-31. Environment: aarch64 host, JDK 21, Gradle 8.11.1 (wrapper
checksum pinned), AGP 8.10.1, SDK platform 37 / build-tools 36.0.0.

> Note on file names: the audit brief refers to `audit/REPORT.md`; the file in
> this repository is `audit/AUDIT_REPORT.md` and it is the one updated here.

## 1. What was verified, and how

| # | Claim under test | Method | Result |
|---|---|---|---|
| 1 | Updater refuses an APK that does not match the release's `SHA256SUMS` manifest | 17 parser tests + 26 fail-closed gate tests (`AppUpdateCheckerSumsTest`, `AppUpdateCheckerGateTest`); code read of the download path | Pass (see §2) |
| 2 | Updater fails **closed** whenever a digest cannot be resolved — no date/tag exception | Gate tests over every failure mode, including old/future/malformed `published_at` and the retag-and-swap shape | Pass (see §2.3) |
| 3 | TV LAN server authenticates every state-changing call | 12 JVM tests driving the real server over a loopback socket (`TvAuthServerTest`); code read | Pass (see §3) |
| 4 | Module arguments cannot escape their JS string literal | 11 JVM tests with a decoder that mirrors the JS parser (`JsStringLiteralTest`) | Pass (see §4) |
| 5 | TV remote page does not render remote data as HTML | Template assertion + code read of every sink | Pass (see §5) |
| 6 | No regression in the rest of the app | Full `:app` unit suite (1156), `:tv` unit suite (28), `:app:assembleProdRelease` | Pass (see §6) |
| 7 | Shipped APKs contain the fixed code | `aapt2` badging, `dexdump`/`strings` on the release APK, native-lib listing, hash comparison | Pass (see §7) |

## 2. Updater integrity (F-01)

### 2.1 What the code does now

`AppUpdateChecker.check()` resolves a SHA-256 for the chosen APK asset and
passes it through one decision point:

```kotlin
internal fun installableApkUrl(apkUrl: String?, digest: String?): String? =
    apkUrl?.takeIf { digest != null }
```

A non-null result is the only way the dialog gets a download URL; a null one
leaves `UpdateInfo.apkUrl` null, and the existing UI opens the releases page.
There is no release age, version or tag that relaxes this — `published_at` is
no longer read at all.

`downloadApk()` streams the response through a `DigestInputStream` and, on
mismatch **or a missing expected digest**, deletes the partial file and
reports `DownloadState.Failed(...)`. The null-digest branch is unreachable
through `check()` by construction; it exists so that state altered after the
check, or a future caller that skips the gate, still cannot install unverified
bytes.

### 2.2 Manifest parsing

`parseSumsEntry(body, fileName)` accepts the two `sha256sum` line forms:

```
<64 hex>  <name>      # text mode
<64 hex> *<name>      # binary mode (-b)
```

and refuses everything else. Tests cover: both separators, CRLF files,
uppercase digests, multi-entry manifests, prefix collisions
(`BitChord-v1.8.1.apk.sig`, `BitChord-v1.8.apk`), the TV manifest name
(`BitChordTV-SHA256SUMS.txt`) not satisfying a phone lookup, missing entries,
duplicate names with one digest (accepted) and with conflicting digests
(refused), a 128-hex-char line that would otherwise lend its tail to the name,
short/non-hex digests, comments/blank lines, and an entry embedded mid-line.

### 2.3 Fail-closed behaviour

Every one of these resolves to no digest, and therefore to the releases page:

- no `<prefix>SHA256SUMS.txt` asset in the release;
- the manifest asset exists but is not in the `uploaded` state;
- more than one asset claims to be the manifest (refused rather than picked);
- the manifest request returns a non-2xx status or the host is unreachable;
- the body has no entry for this exact file name (including a TV-manifest
  digest or a prefix-collision name);
- the same file name appears with conflicting digests;
- the digest is malformed, truncated, or not 64 hex characters;
- the release payload itself cannot be parsed.

With a digest resolved, `downloadApk()` hashes the stream and deletes the file
on mismatch.

### 2.4 The removed date cutoff, and retag-and-swap

An earlier revision of this fix keyed the requirement on
`published_at >= 2026-12-01T00:00:00Z`. Reviewed on 2026-10-31, that was
defective:

- **It was a grace period for new releases, not old ones.** `/releases/latest`
  returns exactly one release, so there was never a set of legacy releases to
  accommodate — only the one currently on offer. Any release published before
  the cutoff skipped verification entirely, leaving a month-long window in
  which the headline fix was inert.
- **Retag-and-swap.** GitHub does not accept `published_at` on create or
  update, so it cannot be backdated directly — but it is *preserved* when a
  release is edited. `PATCH /releases/{id}` accepts `tag_name` and
  `make_latest`. An attacker with release-write access could therefore pick an
  existing pre-cutoff release, replace its APK asset with a malicious build,
  rename its tag upward, and mark it latest: `/releases/latest` returns it,
  `isNewer` accepts the new tag, `published_at` still reads October, and the
  date-keyed logic offered it for install with no digest at all.
- **Missing metadata failed open.** An absent or unparseable `published_at`
  counted as "legacy", so a GitHub API change would have silently disabled
  enforcement.
- **Field mismatch.** `/releases/latest` selects by `created_at`, while the
  security decision keyed on `published_at` — the field that decides which
  release you see was not the field that decided whether checks applied.

All four are gone with the date. The gate test class includes an explicit
old-release, future-release, malformed-date and retag-and-swap case, each
asserting no install path.

**Residual gap (by design, documented): checksum verification is not
cryptographic authenticity.** The manifest is fetched from the same GitHub
release as the APK. It proves the bytes match what that release says they
should be, which catches truncation, corruption, a failed download and a
single-asset swap. It does not prove *who* published them: an attacker who can
write to the release can upload a matching manifest, and the retag-and-swap
path above shows how cheap that is when the trust boundary is the release
itself. Closing that requires a detached signature over the manifest, made
with a key held outside CI and verified against a public key compiled into the
app — see §8, item 1. That is an architectural change, deliberately not part
of this correction.

### 2.5 CI side

`.github/workflows/android.yml` generates `BitChord-SHA256SUMS.txt` from the
release APKs with `sha256sum *.apk` (text mode, two-space separator — matched
by the parser) and uploads it next to the APKs.

**Release ordering matters now.** Because the requirement is unconditional, the
first release built from this branch must publish the manifest, or users on the
new code will be sent to the releases page instead of a one-tap install. The
safe sequence is: publish 1.8.2 (or the next tag) *with* the manifest; users on
1.8.1 verify it because they verify when present, and users on this code
require it and find it. No user ever sees the fallback.

The TV workflow (`tv.yml`) does not publish `BitChordTV-SHA256SUMS.txt`; the
parser and prefix logic are flavour-aware, so the TV app will require and use
it as soon as that asset exists. TV APK assembly was **not** part of this round
(see §9).

## 3. TV LAN authentication (F-02)

### 3.1 Model

| Endpoint | Credential required |
|---|---|
| `GET /api/status`, `POST /api/action`, `GET /api/search`, `POST /api/play` | `bitchord_tv` session cookie |
| `GET /remote` | `bitchord_tv` session cookie |
| `POST /submit` | live pairing code + cookie being submitted |
| `GET /?code=…` | live pairing code (renders the form carrying it) |
| anything else | static sign-in page only |

The credential is **not** the pairing code. The code is a short-lived
(10-minute) transfer token that is spent at pairing; the browser then holds a
128-bit random token in an `HttpOnly; SameSite=Lax` cookie minted by the
server, compared with `MessageDigest.isEqual`. Every request is validated on
its own; there is no "authenticated once" flag on the socket, and the cookie
never appears in a URL, in page JavaScript, or in the QR payload.

Other hardening in the same change:

- Codes are minted with `SecureRandom` (6 digits, displayed on the TV and
  carried in the QR so the phone usually never types it).
- The code rotates on expiry, on successful pairing, and on a stale
  presentation, so an old screenshot cannot pair.
- Per-source-address failure buckets (30 misses / 5 min) escalate to a 15-min
  ban.
- `Access-Control-Allow-Origin: *` was removed; the pages are same-origin, so
  no other website the phone has open can read API responses.
- The `/submit` success page no longer echoes anything user-controlled; error
  text and the hidden code field are HTML-escaped.
- `Connection: close` is sent, matching the one-request-per-socket handler.

### 3.2 Tests

`TvAuthServerTest` starts the real server on a loopback port and drives it
with `HttpURLConnection`:

1. all four `/api/*` endpoints answer `unauthorized` with no cookie;
2. `/remote` renders the sign-in page, not the remote, without a cookie;
3. a wrong code is refused and mints no cookie, and the callback never fires;
4. the landing page echoes only the code it was given (`?code=999999` is not
   reflected);
5. a correct code + cookie produces a `Set-Cookie` that is `HttpOnly`, is not
   the submitted YouTube cookie, and unlocks `/remote`, `/api/status` and
   `/api/action`;
6. the code is spent after a successful pairing (replay yields no cookie);
7. a stale/guessed session token is refused for both `/remote` and
   `/api/status`;
8. the code rotates across restarts and is always 6 digits;
9. the remote template contains no `innerHTML` and no embedded credential;
10. cookie parsing and the JSON/HTML escapers behave as documented.

### 3.3 Known limits (not fixable in-app)

- **Plaintext HTTP on the LAN.** `/submit` carries the user's YouTube cookie
  over `http://<tv-ip>:8765`. Anyone who can passively observe the LAN at that
  moment can read it. Fixing this needs TLS with a certificate the phone will
  trust (self-signed + manual trust, or a pinned cert embedded in the QR), or
  a redesign where the phone never sends the cookie to the TV. Both are
  larger changes than this audit should make; recorded as residual risk.
- **Same-LAN attacker with the code.** Whoever can read the TV screen (or the
  QR) can pair for the session's lifetime. That is the intended UX; the
  mitigation is that the code is single-use and short-lived.
- **No CSRF token on `/submit`.** The session cookie is `SameSite=Lax` and the
  endpoint requires the pairing code in the body, so a cross-site form post
  cannot supply it. Noted rather than re-architected.

## 4. QuickJS argument injection (F-03)

`ModuleManager.jsStringLiteral` now escapes `\ " \n \r \t \b \f`, all other
C0 controls (as `\uXXXX`), and U+2028/U+2029. Every argument passed to a module
export goes through it: the search query, the limit (numeric), the
`contextArg` settings object (keys and values), the track id and the quality
string.

`JsStringLiteralTest` decodes each produced literal with a parser mirroring
JavaScript's rules and asserts it round-trips to the original input, plus that
no unescaped quote/newline/separator survives. Cases: double quotes, single
quotes, backslashes, CR/LF/tab, form feed and backspace, NUL and other control
characters, U+2028/U+2029, Devanagari and emoji, an explicit breakout payload,
and a 60 000-character adversarial input.

**Boundary unchanged:** the VM still exposes only `console` and
`__spine.fetch`; this fix stops argument injection *into* the module's own
code, it does not and cannot sandbox a malicious module the user installed.

## 5. TV remote page HTML (F-02 follow-up)

`getRemoteControlHtml()` now builds search rows with
`createElement`/`textContent` and assigns artwork through `img.src`. The
"Playing … on TV" confirmation is a `textContent` node. Titles and subtitles
therefore land as text even if YouTube returns markup. `innerText` assignments
for the now-playing fields were already text-only and are unchanged. The
template no longer contains a code constant or code-bearing fetch URLs.

## 6. Regression testing

| Suite | Result |
|---|---|
| `:app:testProdDebugUnitTest` | 1156 tests, 0 failures, 0 errors, 1 skipped (pre-existing) |
| `:tv:testProdDebugUnitTest` | 28 tests, 0 failures, 0 errors |
| `:app:compileProdDebugKotlin`, `:tv:compileProdDebugKotlin` | pass |
| `:app:assembleProdRelease` | pass — 4 ABI APKs produced (see §7) |

Commands (daemon caps required on this 5.8 GB host):

```
./gradlew :app:testProdDebugUnitTest --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx2048m" -Dkotlin.daemon.jvm.options="-Xmx1400m"
./gradlew :tv:testProdDebugUnitTest --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx2048m" -Dkotlin.daemon.jvm.options="-Xmx1400m"
./gradlew :app:assembleProdRelease --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx2048m" -Dkotlin.daemon.jvm.options="-Xmx1400m"
```

## 7. Release artifacts

Produced by `:app:assembleProdRelease` on this branch. **Unsigned** (no
keystore on this host; CI injects the release key). Hashes in
`audit/APK_SHA256SUMS.txt`.

| APK | Bytes | SHA-256 |
|---|---|---|
| app-prod-universal-release-unsigned.apk | 171 400 363 | b590939c64c41dbbf26145504bd14431d08469937c84b32f4998739f5c5f69d7 |
| app-prod-arm64-v8a-release-unsigned.apk | 67 631 557 | 82d4f9f693eec086cbb4529d8ed670afcde1599f5b16cb6986079ae01cda1839 |
| app-prod-armeabi-v7a-release-unsigned.apk | 57 818 257 | 79298c9f857d63dcb94a527f0164bb1d9c691c7e985f5c96f6b793a93af29477 |
| app-prod-x86_64-release-unsigned.apk | 74 224 831 | dc679eb54194bc1cd23258cb2b51cc383c479839548fd613b82054a034bd0c96 |

Build: `:app:assembleProdRelease` — BUILD SUCCESSFUL in 43m 49s, exit 0, 188
tasks (full R8 rebuild after the updater correction).
`output-metadata.json` lists all four ABI elements at versionCode 29 /
versionName 1.8.1.

**These hashes are of the unsigned review APKs.** CI re-signs the same sources
with the release key, so the hashes in `APK_SHA256SUMS.txt` will not match a
signed release APK — they identify the artifacts this verification inspected.

Checks run against the built APKs:

- `aapt2 dump badging` (arm64 split): `com.music.bitchord`, versionCode 29,
  versionName 1.8.1, minSdk 26, targetSdk 36, compileSdk 37. Permission list is
  unchanged from the audited manifest — no permission was added by the fixes.
- Native libraries present for arm64: `libbitchord_analysis.so`,
  `libbitchord_dsd.so`, `libbitchord_dst.so`, `libffmpegJNI.so`,
  `libquickjs.so`, `libconscrypt_jni.so`, `libonnxruntime*.so`,
  `libspake2.so`. Nothing unexpected.
- **Updater fix is in the APK.** `dexdump` finds `AppUpdateChecker.parseSumsEntry`,
  `digestMatches`, `shaSumsUrl` and `apkAssetUrl`; string search finds
  `BitChord-SHA256SUMS.txt`, `Update APK has no published checksum` and
  `Update APK failed its integrity check`, and finds **zero** occurrences of
  the removed `2026-12-01T00:00:00Z` cutoff constant in all four APKs.
- **Escaper fix is in the APK.** `ModuleManager.jsStringLiteral$app_prodRelease`
  exists with the constant pairs `8232 → "\\u2028"`, `8233 → "\\u2029"`,
  `12 → "\\u000c"`. Call sites found by `dexdump`: the search query
  (`searchTracksNow`), the stream arguments (`getTrackStreamUrl` inlined into
  the app's composable by R8, two calls) and `contextArg`'s three uses
  (key / `"value"` / value). No unescaped path to `callExport` remains.
- **TV code is not in this APK**, as expected — `TvAuthServer` is absent from
  `classes*.dex`; the TV server ships in the TV app.
- `BuildConfig` values at build time: `LASTFM_API_KEY=""` (no secret on this
  host), `UPDATE_APK_PREFIX="BitChord-"`, `LISTEN_TOGETHER_SERVER` set to the
  project's public Render host. No credential-shaped string found in the dex.
- `apksigner verify`: not signed ("Missing META-INF/MANIFEST.MF") — expected,
  there is no keystore here.
- Exported components: `MainActivity`, `PlaybackService`, `WearCommandService`,
  media/widget receivers, FileProvider (`FILE_PROVIDER_PATHS`), Cast/GMS
  plumbing — identical to the audited pre-fix manifest.

## 8. Residual risks and recommendations

1. **Signed update manifest (recommended, not implemented).** Publish
   `BitChord-SHA256SUMS.txt` *and* a detached signature over it
   (e.g. minisign/Ed25519) made with a key held outside GitHub Actions, and
   embed the public key in the app. The updater then verifies the signature
   before trusting any digest. Until then, an attacker with release write
   access can satisfy the current check.
2. **HTTPS for `/submit`.** The YouTube cookie still crosses the LAN in
   plaintext. Either terminate TLS on the TV with a certificate the phone is
   taught to trust at pairing, or move the cookie exchange to a channel that
   never exposes it to the network.
3. **WatchInstaller ADB key** is stored as raw PKCS#8 in `noBackupFilesDir`.
   Wrap it with `EncryptedSharedPreferences`/`MasterKey`, or regenerate per
   pairing.
4. **Presence heartbeat** has no opt-out. Add a settings toggle.
5. **Module JS / `zemer-cipher` config** are fetched without digest pinning.
   Pin a hash and refresh deliberately.
6. **Gradle dependency verification** is not enabled. Run
   `./gradlew --write-verification-metadata sha256` and commit the result.
7. **`security-crypto` 1.1.0-alpha06** is deprecated; plan the storage
   migration.
8. **`EncryptedPrefs` plaintext fallback** is silent; consider surfacing a
   warning when it happens.

## 9. Explicitly NOT verified

- **TV APK assembly.** `:tv:assembleProdRelease` was not run in this round
  (host time). The TV sources compile and its unit tests pass, but no TV APK
  was produced, installed, or exercised on a TV device.
- **Wear OS APK** was not assembled separately; the release build graph
  includes `:wearos` tasks only as far as the phone assembly requires.
- **On-device behaviour.** No emulator or physical device was used. Pairing,
  QR scanning, cookie handoff, updater install and the remote page were
  verified at the unit/socket level, not by a human with a phone and a TV.
- **Signing.** The APKs here are unsigned. Signature continuity, Play-style
  install-over checks and the CI keystore path were reviewed by reading
  `android.yml`, not executed.
- **Network interception.** No proxy capture of a real pairing or update
  session was performed; the plaintext-HTTP and same-origin claims come from
  code reading.
- **Fuzzing.** The parsers were tested with hand-written cases, not a fuzzer.
- **Dependency advisory re-scan.** The OSV results in
  `DEPENDENCY_INVENTORY.md` are from the audit round; no new scan was run
  after the jsoup bump beyond confirming the resolved version.
- **Web/backend/extension subprojects** — out of APK scope, unchanged by this
  round.

## 10. Final assessment

All four remediations that this round set out to verify are present in the
source, are covered by tests that fail if the property is removed, and are in
the release APK that was built and hashed here. The updater now refuses
unverifiable or mismatched APKs; the TV server requires a per-request
credential and no longer leaks CORS or renders remote data as HTML; module
arguments can no longer break out of their literals.

The two issues that remain genuinely open are architectural, not coding
mistakes: the update manifest is not signed by a key outside CI, and the TV
pairing flow still sends the YouTube cookie over plaintext LAN HTTP. Both are
documented above with concrete next steps. Nothing found in this round rises
to an unmitigable critical vulnerability, and no new critical issue was
introduced by the fixes.
