# BitChord Dependency Inventory

`:app` prodRelease runtime classpath; generated on the audit branch:
`./gradlew :app:dependencies --configuration prodReleaseRuntimeClasspath` (Gradle 8.11.1 / AGP 8.10.1).

## Direct dependencies (declared → resolved)

| Module | Declared | Resolved |
|---|---|---|
| org.jetbrains.kotlin:kotlin-stdlib | 2.3.20 | 2.4.10 |
| androidx.compose:compose-bom | 2024.12.01 | (BOM) |
| androidx.compose.foundation:foundation | 1.10.0 | 1.10.5 |
| androidx.compose.material3:material3 | via BOM | 1.3.1 |
| androidx.activity:activity-compose | 1.9.3 | 1.9.3 |
| androidx.navigation:navigation-compose | 2.8.5 | 2.8.5 |
| androidx.lifecycle:lifecycle-viewmodel-compose, -runtime-ktx, -process | 2.8.7 | → 2.9.4 |
| androidx.core:core-ktx | 1.15.0 | 1.16.0 |
| androidx.appcompat:appcompat | 1.7.0 | 1.7.0 |
| androidx.media3:media3-exoplayer, -session, -common, -datasource-okhttp, -exoplayer-hls, -exoplayer-dash | 1.11.0 | 1.11.0 |
| io.coil-kt.coil3:coil-compose, -network-okhttp, -svg | 3.0.4 | 3.0.4 |
| androidx.palette:palette-ktx | 1.0.0 | 1.0.0 |
| dev.chrisbanes.haze:haze, haze-materials | 1.3.1 | 1.3.1 |
| com.google.zxing:core | 3.5.3 | 3.5.3 |
| com.halilibo.compose-richtext:richtext-ui-material3, -commonmark | 0.20.0 | 0.20.0 |
| io.ktor:ktor-client-core, -okhttp, -content-negotiation, -serialization-kotlinx-json, -websockets | 3.5.2 | 3.5.2 |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.11.0 | 1.11.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-guava | 1.9.0 | 1.11.0 |
| com.github.MetrolistGroup.innertubex:innertubex-android (JitPack) | v0.7.0 | v0.7.0 |
| com.github.TeamNewPipe:nanojson (JitPack, commit pin e9d656ddb49a412a5a0a5d5ef20ca7ef09549996) | commit | commit |
| org.jsoup:jsoup | 1.22.2 | 1.23.2 on branch (see advisories) |
| org.mozilla:rhino, rhino-engine | 1.8.1 | 1.8.1 |
| androidx.security:security-crypto | 1.1.0-alpha06 | 1.1.0-alpha06 |
| io.github.dokar3:quickjs-kt-android | 1.0.14 | 1.0.14 |
| com.hierynomus:smbj | 0.15.0 | 0.15.0 |
| com.microsoft.onnxruntime:onnxruntime-android | 1.28.0 | 1.28.0 |
| com.google.android.gms:play-services-cast-framework | 22.2.0 | 22.2.0 |
| androidx.mediarouter:mediarouter | 1.8.1 | 1.8.1 |
| com.google.android.gms:play-services-wearable | 20.0.1 | 20.0.1 |
| androidx.wear:wear-remote-interactions | 1.1.0 | 1.1.0 |
| com.github.MuntashirAkon:libadb-android (JitPack) | 3.1.1 | 3.1.1 |
| org.conscrypt:conscrypt-android | 2.5.3 | 2.5.3 |
| com.google.code.findbugs:jsr305 | 3.0.2 | 3.0.2 |
| com.google.protobuf:protobuf-javalite | 4.35.0 | 4.35.0 |

Notable transitives: OkHttp/Okio (via Ktor, Coil, shared's Http.kt), SMACK-style mdns none, BouncyCastle (predominantly via libadb-android + SMBJ), slf4j 2.0.18, kotlinx-io 0.9.1.

## Advisory check (OSV.dev query batch, 2026-10-31)

| Package:version | Result |
|---|---|
| org.jsoup:jsoup **1.22.2** | GHSA-65r4-943x-97jj (HIGH): memory exhaustion in XmlTreeBuilder through deeply nested namespace declarations, fixed 1.23.2; also GHSA-pmhh-3w7g-xqp8 (MODERATE): Cleaner raw-text bypass, fixed 1.23.1. Remediation on this branch: bumped `org.jsoup:jsoup` to **1.23.2** in `app/`, `shared/`, `tv/` build files. |
| All other listed coordinates | No published OSV advisories at the resolved versions. |

## Supply-chain notes
- JitPack dependencies carry no in-repo checksums — JitPack rebuilds from git commits, so the resolved `innertubex-android v0.7.0` and `libadb-android 3.1.1` reflect those tags as built by JitPack at resolution time. Their sources are reviewable on GitHub; recommend vendors publish checksums of the AARs they consume (a repo-level `dependency verification` lock is possible: `./gradlew --write-verification-metadata`) — suggested follow-up, not done on this branch.
- `nanojson` pinned by full commit hash (reproducible).
- `androidx.security:security-crypto` is the Google-deprecated alpha; no known CVE, but worth migrating to a maintained local-storage approach.
