# BitChord Security Audit Report

Branch `audit/build` (off `main` `08cb6ce8`; Android sources identical to the `v1.8.1` published release). Date: 2026-10-31. Auditor: repo security review on an isolated sandbox with a locally reproduced build.

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
| F-01 | Updater downloads APK with no digest check | MEDIUM-LOW | **Patch built**: verifies against new `BitChord-SHA256SUMS.txt` release asset (fail-open only for releases predating the manifest) |
| F-02 | TV LAN server: unauthenticated control + `/submit` cookie over plaintext | MEDIUM | **Patch built**: SecureRandom 6-digit pairing code enforced on `/api/*`, `/submit`, `/remote`; QR + screen display; per-IP miss throttling |
| F-03 | Module-call JS string interpolation (search query/settings/trackId) into QuickJS | LOW-MEDIUM | **Patch built**: all arguments now pass the `jsStringLiteral` escaper; VM sandbox boundary unchanged (console + `__spine.fetch` only) |
| F-07 | Wrapper jar had no verifiable provenance; distribution zip unverified | LOW | **Patch built**: official 8.11.1 wrapper files + `distributionSha256Sum` pinned |
| — (deps) | jsoup 1.22.2 — GHSA-65r4-943x-97jj (HIGH, DoS via XML namespaces) + GHSA-pmhh-3w7g-xqp8 | HIGH | **Patch built**: bumped to 1.23.2 in `app`/`shared`/`tv` |
| F-04 | Module JS + `zemer-cipher` `player_configs.json` fetched without integrity pinning; module index is user-configured (no default URL) | LOW | Documented; recommendation: pin digests of fetched config |
| F-05 | Presence heartbeat (random UUID, platform, state) has no opt-out | LOW | Documented; recommend a settings toggle |
| F-06 | Phone↔watch ADB private key stored unencrypted | LOW-MEDIUM | Documented; recommend EncryptedSharedPreferences |
| F-09 | EncryptedPrefs plaintext fallback on broken KeyStore | LOW | Documented |
| F-08/F-10/F-11 | Spotify web-player bundle regex parse; exported GMS Wear receiver; many unauthenticated public lyric endpoints | INFO | No action required |
| — (TV ui) | `/remote` page builds result rows from YouTube titles via `innerHTML` | LOW | Browser-only surface behind pairing post-fix; recommend textContent or escaping in a follow-up |

## Verification performed on this branch
- `./gradlew :app:compileProdDebugKotlin :tv:compileProdDebugKotlin :wearos:compileProdDebugKotlin` — pass.
- `./gradlew :app:testProdDebugUnitTest` — **1102 tests, 0 failures, 0 errors** (1 skipped, pre-existing).
- `./gradlew :app:assembleProdDebug` and `:app:assembleProdRelease` — pass; native libs built per-ABI; R8 completed.
- Wrapper bootstrap ran under the new `distributionSha256Sum` (verified at download time by Gradle).
- `aapt2 dump badging` on final APKs: `com.music.bitchord` v1.8.1 (versionCode 29), targetSdk 36, minSdk 26; `universal`/`arm64-v8a`/`armeabi-v7a`/`x86_64` splits; no permission additions.

## Post-fix release artifacts (unsigned — review copies; CI signs releases)
| APK | SHA-256 |
|---|---|
| app-prod-universal-release-unsigned.apk | a1f2d37f56471778ac007a65d9f6310202e93896d9ca45d20467d74646201634 |
| app-prod-arm64-v8a-release-unsigned.apk | e1d9062f348a01febd83752d2d8e215098d4d50fc152da4fe432bb0d7381d98e |
| app-prod-armeabi-v7a-release-unsigned.apk | a01bb45f8db1f74ee3655563eb2f818de16925db1ed7763c60ce3233fee7f46c |
| app-prod-x86_64-release-unsigned.apk | 4ac2889ec9e51c2c9540403c2bf94db831f7170418988774811320974d712b4c |

(same table as audit/APK_SHA256SUMS.txt, produced by `sha256sum` on the outputs of the final `:app:assembleProdRelease`).

## Residual risks / recommendations (not implemented here)
1. Publish `BitChordTV-SHA256SUMS.txt` alongside TV release APKs (TV APKs are assembled/released manually); the TV updater will honor its prefix-manifest once present (F-01 mechanism is prefix-aware).
2. Consider `--write-verification-metadata` locking for Gradle dependencies, and/or checksum pinning for the JitPack artifacts and the `zemer-cipher` config fetch (F-04).
3. Presence opt-out toggle (F-05) — product decision, one line of settings + one condition in `AndroidPresence`.
4. Store the WatchInstaller ADB key under EncryptedSharedPreferences (F-06).
5. TV remote page: stop interpolating YouTube titles through `innerHTML` (low, browser-local).
6. `security-crypto` is a deprecated alpha — plan a storage migration.

## Environment notes
The build host is aarch64-only; the NDK toolchain (x86_64) was transparently run under qemu-user binfmt and the native outputs verified inside the APKs. `local.properties` here holds only `sdk.dir=/opt/android-sdk`. No keystores or credentials were created or stored on this machine.
