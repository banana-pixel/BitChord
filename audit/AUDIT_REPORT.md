# BitChord Security Audit Report

Branch `audit/build` (off `main` `04a4a838`; Android sources identical to the `v1.8.1` published release). Date: 2026-10-31. Auditor: repo security review on an isolated sandbox with a locally reproduced build. Independent re-verification of every shipped fix: `audit/FINAL_VERIFICATION.md`.

## Executive verdict
The codebase is in good shape for a sideloaded music app: permissions are conservative, all traffic is HTTPS-only by manifest, cookies and tokens stay on the device or go to their own providers, no dynamic dex loading, no custom trust managers, and the concurrency/IPC surfaces are conventional. Two real network-safety gaps were found and **patched on this branch** (TV LAN auth server, updater integrity), plus one HIGH dependency advisory (jsoup) patched, a supply-chain hardening for the Gradle wrapper, and a JS-injection fix inside the QuickJS module sandbox.

Everything else observed is low/informational (documented in FINDINGS.md with recommendations).

## Scope and method
- Remote: `https://github.com/kushagrasinghx/BitChord` (main + tags/branches; AI-generated PR series `ccr-*` reviewed for build/signing tampering — none found).
- Static: full read of auth (`EncryptedPrefs`, `AuthStore`, `WebSession`, login screens, TokenBridge), playback & providers (InnerTube/InnertubeX/PoToken/Spotify/Tidal/Apple), addons & modules (ModuleIndex/ModuleManager/QuickJsExecutor/SpineModule/AddOnClient), WebDAV/SMB, watch (WatchInstaller, WearProtocol, SelfUpdate), TV (manifest, TvAuthServer, dialogs), presence/community canvas, CI workflows, wrapper/provenance, backend (Go) and web subprojects (out-of-APK, hygiene only).
- Endpoints: every URL string sourced to its use (NETWORK_AND_DATA_FLOWS.md) — host set is exactly the music ecosystem + project services + user-configured transports; nothing unexpected.
- Dependencies: resolved runtime classpath generated and cross-checked against OSV.dev (DEPENDENCY_INVENTORY.md).
- Build: full local reproduction on aarch64 (see BUILD_PROVENANCE.md), debug + release, plus 1102 unit tests green post-fix.

## Findings summary (full detail in FINDINGS.md)
| ID | Area | Severity | Status on branch |
|---|---|---|---|
| F-01 | Updater downloads APK with no digest check | MEDIUM-LOW | **Fixed & verified**: verifies against new `BitChord-SHA256SUMS.txt` release asset; manifest-mandatory from 2026-12-01 (fail-closed) |
| F-02 | TV LAN server: unauthenticated control + `/submit` cookie over plaintext | MEDIUM | **Fixed & verified**: SecureRandom pairing code (single-use, 10-min) plus `HttpOnly` session cookie checked on every `/api/*` and `/remote`; per-IP miss throttling; `*` CORS removed. Residual: `/submit` still plaintext HTTP |
| F-03 | Module-call JS string interpolation (search query/settings/trackId) into QuickJS | LOW-MEDIUM | **Fixed & verified**: all arguments pass `jsStringLiteral` (incl. U+2028/2029); VM sandbox boundary unchanged |
| F-07 | Wrapper jar had no verifiable provenance; distribution zip unverified | LOW | **Fixed & verified**: official 8.11.1 wrapper files + `distributionSha256Sum` pinned |
| — (deps) | jsoup 1.22.2 — GHSA-65r4-943x-97jj (HIGH, DoS via XML namespaces) + GHSA-pmhh-3w7g-xqp8 | HIGH | **Fixed & verified**: bumped to 1.23.2 in `app`/`shared`/`tv` |
| F-04 | Module JS + `zemer-cipher` `player_configs.json` fetched without integrity pinning; module index is user-configured (no default URL) | LOW | Documented; recommendation: pin digests of fetched config |
| F-05 | Presence heartbeat (random UUID, platform, state) has no opt-out | LOW | Documented; recommend a settings toggle |
| F-06 | Phone↔watch ADB private key stored unencrypted | LOW-MEDIUM | Documented; recommend EncryptedSharedPreferences |
| F-09 | EncryptedPrefs plaintext fallback on broken KeyStore | LOW | Documented |
| F-08/F-10/F-11 | Spotify web-player bundle regex parse; exported GMS Wear receiver; many unauthenticated public lyric endpoints | INFO | No action required |
| — (TV ui) | `/remote` page builds result rows from YouTube titles via `innerHTML` | LOW | **Fixed & verified**: rows built with `createElement`/`textContent`; template assertion test added |

## Verification performed on this branch
- `./gradlew :app:compileProdDebugKotlin :tv:compileProdDebugKotlin :wearos:compileProdDebugKotlin` — pass.
- `./gradlew :app:testProdDebugUnitTest` — **1130 tests, 0 failures, 0 errors** (1 skipped, pre-existing). Includes the 17 updater-manifest tests and 11 module-escaper tests added in the verification round.
- `./gradlew :tv:testProdDebugUnitTest` — **28 tests, 0 failures, 0 errors**, including 12 socket-level tests of the TV pairing/auth server.
- `./gradlew :app:assembleProdDebug` and `:app:assembleProdRelease` — pass; native libs built per-ABI; R8 completed.
- Wrapper bootstrap ran under the new `distributionSha256Sum` (verified at download time by Gradle).
- `aapt2 dump badging` on final APKs: `com.music.bitchord` v1.8.1 (versionCode 29), targetSdk 36, minSdk 26; `universal`/`arm64-v8a`/`armeabi-v7a`/`x86_64` splits; no permission additions.

Independent re-testing of each fix, with method and residual limits, is in **`audit/FINAL_VERIFICATION.md`**.

## Post-fix release artifacts (unsigned — review copies; CI signs releases)
Built by `:app:assembleProdRelease` on this branch (BUILD SUCCESSFUL, 5m 6s,
exit 0). Same four lines as `audit/APK_SHA256SUMS.txt`.

| APK | SHA-256 |
|---|---|
| app-prod-universal-release-unsigned.apk | b8fc63a5d88141fc0b48020105decddf4ff1b2b65c149c74c1357ee09962a050 |
| app-prod-arm64-v8a-release-unsigned.apk | 362cd4851250bb540593c20ca0471a408958176f9d29f0daf8a72aa5f49b1540 |
| app-prod-armeabi-v7a-release-unsigned.apk | 02d1af4a1c4c8e8f8e202032056f64c5d7b25fe5c9e0b0cbf7bac92ea4ab2421 |
| app-prod-x86_64-release-unsigned.apk | 661ae2ed44cdc1dac39b4b33b130c0b35c9a99039547b722dcca4d1badf49bb0 |

These are unsigned review artifacts; CI re-signs the same sources with the
release key, so signed release hashes will differ. APK-level inspection
(badging, native libs, exported components, and confirmation that the fixed
updater/escaper code is in the dex) is recorded in `BUILD_PROVENANCE.md` and
`FINAL_VERIFICATION.md` §7.

## Residual risks / recommendations (not implemented here)
1. **Signed update manifest.** The digest now comes from the same GitHub release as the APK, so it stops corruption and single-asset swaps but not an attacker with release write access. Publish a detached signature over `BitChord-SHA256SUMS.txt` (Ed25519/minisign) with a key held outside CI, and embed the public key in the app.
2. **HTTPS for `/submit`.** The YouTube cookie still crosses the LAN in plaintext during pairing; terminate TLS with a certificate the phone is taught to trust at pairing, or move the cookie exchange off the network.
3. Consider `--write-verification-metadata` locking for Gradle dependencies, and/or checksum pinning for the JitPack artifacts and the `zemer-cipher` config fetch (F-04).
4. Presence opt-out toggle (F-05) — product decision, one line of settings + one condition in `AndroidPresence`.
5. Store the WatchInstaller ADB key under EncryptedSharedPreferences (F-06).
6. `security-crypto` is a deprecated alpha — plan a storage migration.
7. Publish `BitChordTV-SHA256SUMS.txt` alongside TV release APKs (TV APKs are assembled/released manually); the updater's prefix logic already accepts it.
8. TV APK assembly and on-device pairing/update flows were **not** exercised in this round — see `FINAL_VERIFICATION.md` §9.

## Environment notes
The build host is aarch64-only; the NDK toolchain (x86_64) was transparently run under qemu-user binfmt and the native outputs verified inside the APKs. `local.properties` here holds only `sdk.dir=/opt/android-sdk`. No keystores or credentials were created or stored on this machine.
