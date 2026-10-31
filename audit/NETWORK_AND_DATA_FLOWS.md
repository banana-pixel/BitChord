# BitChord Network & Data Flows — Endpoint Inventory

Static analysis of all source strings in `app`, `tv`, `wearos`, `shared`, `sharedUi` (branch audit/build @ main).

## Primary music/streaming hosts (YouTube Music)
- `music.youtube.com/youtubei/v1` — InnerTube API client (search/streams/related/account actions). Auth: `SAPISIDHASH` (shared/src/jvmSharedMain/.../data/innertube/Innertube.kt:1263) derived from the user's YTM cookies; anonymous otherwise. Some requests send PoToken.
- `www.youtube.com/api/jnn/v1/Create`, `GenerateIT` — YouTube bot-guard (PoToken) attestation endpoints; unauthenticated, no user data.
- `i.ytimg.com`, `*.googlevideo.com` (resolved per-track) — media/artwork CDN, no auth.
- `accounts.google.com/ServiceLogin` — WebView flow for auth.

## Spotify (source + canvas)
- `accounts.spotify.com`, `api.spotify.com`, `api-partner.spotify.com`, `spclient.wg.spotify.com`, `open.spotify.com`, `clienttoken.spotify.com` — Spotify power-up. Token harvest via WebView cookie (sp_dc) with the TokenBridge scoped to `open.spotify.com`. Web-player bundle (`open.spotifycdn.com`) JS fetched for token extraction — regex only, no evaluation.

## Other catalogs/providers
- `api.tidal.com`, `im-fa.manifest.tidal.com`, `resources.tidal.com`
- `amp-api.music.apple.com`, `itunes.apple.com`

## Lyrics and user-value services
- Lyrics: `lrclib.net`, `lrc.red`, `api-lyrics.simpmusic.org`, `lyrics-plus-backend.vercel.app`, `unison.boidu.dev`, `lyrics-api.binimum.org`, `lyrics-api.boidu.dev`, `apic.musixmatch.com`, `www.musixmatch.com`, `api.paxsenix.org`, `lyrics.kugou.com`, `genius.com`, `www.megalobiz.com`
- Language: `inputtools.google.com` (romanization), `translate.googleapis.com` (translation)
- Scrobbling: `ws.audioscrobbler.com` (Last.fm/Libre.fm), `api.listenbrainz.org`
- Discord RPC: `discord.com/api`, `discord.com/login`, `cdn.discordapp.com` (avatars)

## App-derivative services (project-controlled or community)
- `api.github.com/repos/kushagrasinghx/BitChord/releases/latest` — updater metadata (F-01)
- `bitchord.kushagrasingh.in`, `api.bitchord.kushagrasingh.in/api/presence` — site + presence heartbeat (F-05)
- `vivimusicanvas.mkmdevilmi.workers.dev/canvas.json` — community canvas index (JSON of video ids)
- `raw.githubusercontent.com/ZemerTeam/zemer-cipher/.../player_configs.json` — runtime cipher config (F-04)

## Transports the user configures
- WebDAV — any https/http base URL the user enters (Basic auth; plain HTTP only when the user typed it)
- SMB — local network shares only (SMBJ over TCP; no relay)
- Module sources — user-added index URL; module JS fetched over HTTPS and run in a QuickJS VM limited to `console` + `__spine.fetch` (module-owned OkHttp client; no app cookies). No default module index URL ships in this build.
- Listen-together — party server from BuildConfig `LISTEN_TOGETHER_SERVER` (project's Go server) or a user-entered server; carries only the party code/token and playback-state messages (see backend/README.md).

## Wear / Watch support (both apps)
- Everything crosses the GMS Wearable Data Layer. The watch build has **no INTERNET permission**; its update APK comes via the phone with `PackageInstaller` and the platform's installer continuity check.

## TLS posture
- `usesCleartextTraffic="false"` app-wide; explicit `http://` WebDAV bases are the only user-decided exception.
- No custom `TrustManager`s; OkHttp defaults (Conscrypt when available).
- No proxy configuration; no certificate pinning (matches upstream NewPipe-adjacent flexibility).
- `PoTokenWebView` sets `blockNetworkLoads = true` (only locally generated content runs there).
- TV auth server carries the YTM cookie over LAN plaintext (F-02).

## What leaves the device (user data)
- YouTube Music cookies + SAPISIDHASH → Google (standard for using that account)
- Spotify cookies → Spotify hosts
- Last.fm creds → audioscrobbler API
- Listen-together session messages (track ids, playback state) → party server (user-selectable)
- Presence heartbeat (random install UUID, platform, state flags) → api.bitchord.kushagrasingh.in (F-05)
- Module search/stream queries → the user-configured module server on each call
- WebDAV/SMB — user-configured hosts (basic auth, file contents)
- Library/favorites/lyrics cache stay local (Room DB + app files; `allowBackup=false`).

No traffic goes to hosts beyond the above (each URL constant was traced to its use).
