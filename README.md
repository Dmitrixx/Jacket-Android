# Jacket — Android media center 2.6.0

Jacket is a single Android application for phones, tablets and Android TV / Google TV. It combines a poster-based media catalog, a user-configurable indexer cabinet, an integrated BitTorrent engine and a loopback HTTP media server.

**No separately installed Jackett or TorrServer is required.** Neither service is bundled or launched by Jacket. Direct RSS/Atom and Torznab indexers work independently; an optional connector can also query a Jackett server that the user already runs on a LAN or HTTPS endpoint. Torrent files are handled by the in-process libtorrent4j session and served to ExoPlayer through `127.0.0.1`.

The project is under active development. CI verifies JVM unit tests and APK assembly; real-device streaming, Android TV remote navigation and process-kill recovery still require hands-on acceptance testing.

## Main areas

- **Home / Movies / Series** — poster shelves and grids, ratings, years and details.
- **Search** — catalog search with a short debounce; configurable website-search shortcuts open the matching query in the external browser.
- **Series details** — TMDB seasons and episode lists; an episode search adds its `SxxExx` code to the indexer query.
- **My library** — favorites and recently opened cards.
- **Sources** — native Jackett indexer catalogue, per-tracker search for configured indexers, direct RSS/Torznab connectors, rich release cards and in-app `.torrent` queue actions. Unsupported video services are shown as not connected until a verified adapter exists.
- **Downloads** — magnet and `.torrent` tasks, file list, selected-file priorities, pause/resume, speed and progress.
- **Player** — Media3 / ExoPlayer, seek controls, audio/subtitle track selection, downloaded sidecar subtitles, external-player handoff, progressive video, HLS and DASH playback.
- **Settings** — LAMPA-inspired control center for Filmix device-code pairing, HDRezka account/session, built-in tracker shortcuts, torrent indexers, catalog metadata and network limits.

## Video source catalogue

The in-app source registry preserves the requested priority order for HDRezka, Filmix, ColdFilm, Baskino, Kinogo, VideoCDN, Collaps, Kinobase, CDNMovies and Lordfilm. HDRezka currently has source URL/Cookie settings and Filmix has device-code authorization, but neither has a complete catalogue/stream adapter yet. The remaining providers are unconnected. The UI distinguishes available auth settings from a working media adapter; no undocumented endpoints or pretend integrations are added.

## Filmix and HDRezka accounts

- **Filmix** uses a device-code flow against the documented HTTPS API host: Jacket requests a short-lived code, the user confirms it on the Filmix device page, and the app polls for activation. The permanent device token is encrypted using Android Keystore. No Filmix password is requested. Undocumented hosts and cleartext HTTP fallback are not used.
- **HDRezka** has an explicit HTTPS source field, email/login, password and manual Cookie-session fields. Login responses are inspected through a bounded preview so large HTML pages do not cause a false oversized-response error. The app does not choose or rotate mirrors automatically, and it stops on CAPTCHA, Cloudflare or interactive challenges.
- Credentials and cookies stay on the device and are not included in diagnostics or tracker exports.

These account screens manage authentication state. They do not claim universal playback support for arbitrary protected HTML player pages; direct media playback continues to require a source-published direct URL or a supported feed.

## Built-in tracker shortcuts

The first run seeds search shortcuts for RuTracker, NNM-Club, Rutor, Kinozal, 1337x, The Pirate Bay and Internet Archive. The user can enable, disable, edit or delete them and restore the preset list later. These shortcuts open the selected website in the external browser. They are not site-specific HTML scrapers or native torrent adapters. For in-app structured results, add a Torznab/Newznab or RSS endpoint under Torrent Sources.

## Indexer cabinet and optional Jackett connector

Add user-owned endpoints:

- RSS / Atom feeds and Torznab / Newznab-compatible endpoints over HTTPS.
- HTTP endpoints only when they target a private / loopback / link-local IP or a local DNS suffix such as `.local` or `.lan`; this is intended for a Jackett server on your own network.
- An optional Jackett setup card with server address, API key, tracker filter (`all` by default), connection test and disconnect. Jacket probes Jackett with `t=caps`, then searches its selected tracker set. Jackett remains external and is never installed or bundled.
- API key, HTTP Basic username/password, or a manually supplied session cookie.
- Categories, tags, Freeleech flag, sort field and sort order.
- Enable/disable, edit, individual test, test all and a per-indexer probe search.

A search runs enabled providers in parallel with at most four concurrent requests. A failed provider is reported by name and does not cancel successful peers. Feed responses are limited to 4 MiB, 100 entries per feed and 200 merged results. Connection and read timeouts are 10 and 25 seconds.

Indexer settings can be exported/imported as JSON. **API keys, passwords and cookies are deliberately omitted from exports** and must be entered again after import. Locally stored secrets are encrypted with Android Keystore using AES/GCM.

Example Torznab search template:

```text
https://your-authorized-indexer.example/api?t=search&q={query}
```

Search templates include `{query}`; `{apikey}` is optional. Public indexers must use HTTPS. The adapter understands common Torznab attributes including magnet URL, seeders, peers, size/length, category, Jackett's original tracker name and Torznab error responses. Jackett's `peers` value is treated as total peers; leechers are calculated as peers minus seeders. RSS/Atom enclosures and Media RSS `media:content` may also publish direct HTTPS video URLs (`video/*`, MP4/WebM/MKV), HLS playlists (`.m3u8`) or DASH manifests (`.mpd`). Jacket recognizes those declared media links and plays them directly in Media3; it does not need to inspect arbitrary pages.

Release labels such as 720p / 1080p / 2160p, HDR / DV, HEVC / x264 and common audio/dubbing tags are extracted heuristically from the release title. They are not authoritative tracker metadata. Filters operate on the current results and do not issue another request.

The app does not ship private tracker credentials or site-specific page scrapers. CAPTCHA, anti-bot challenges, proprietary sign-in flows and interactive 2FA are not automated. A manually entered cookie may work where the source permits it.

## User-configured website search

Add up to 50 website search shortcuts with a name and an HTTPS URL template containing `{query}`. When a title is opened, Jacket shows an LAMPA-style source sheet; choosing a configured website opens its search URL in the external browser with the title and year encoded safely.

Example template shape:

```text
https://your-authorized-site.example/search?q={query}
```

Website shortcuts remain link-based and open the site in the browser. Structured in-app playback is supported when a source explicitly publishes a direct HTTPS media URL in an RSS/Atom enclosure or Media RSS `media:content`. Jacket does not scrape arbitrary HTML pages, unpack obfuscated player scripts, automate private logins or CAPTCHA, extract hidden streams, bypass DRM, or circumvent regional access restrictions. Use only services and media you are authorized to access. For a licensed provider, prefer its documented API or feed.

## Torrent engine and local stream server

The app uses one in-process libtorrent4j session:

1. Add a magnet or import a `.torrent` file from the app picker, Android Share, or an HTTPS result.
2. After metadata arrives, inspect the files in the torrent.
3. Select one file to watch. Jacket gives it the highest file priority, marks the other files `IGNORE`, enables sequential download and raises priority for the first pieces and the final piece.
4. ExoPlayer requests the selected file from the local HTTP server while libtorrent is still downloading the required pieces.

The local endpoint:

- Binds only to `127.0.0.1`, using port 8090–8099.
- Supports `GET`, `HEAD`, byte `Range`, `206 Partial Content`, `Content-Range`, `Accept-Ranges` and MIME types based on the filename.
- Parses the requested byte range and maps it to torrent piece indices using the file's absolute offset and the torrent piece length.
- Gives the first missing piece the nearest deadline, then schedules a forward window with increasing deadlines (up to 8 MiB / 32 pieces). A seek replaces the old window and starts prioritizing from the new Range position.
- Waits for the requested pieces to be hash-checked and written to disk before serving those bytes; it does not wait for the entire file.
- Uses a bounded 32 MiB LRU disk ring for recently served byte ranges. Individual segments are at most 256 KiB, overlapping seeks can reuse cached suffixes, and the oldest cache entries are evicted as the ring fills.
- Keeps the number of in-flight stream buffers bounded (one segment per active HTTP worker); it never allocates the full video in RAM.
- The temporary playback cache contains copies only. Evicting cached bytes never deletes libtorrent's verified torrent data, so the torrent's on-disk integrity and fast-resume state remain intact.
- Rejects non-loopback Host headers, malformed hashes, invalid file indices and traversal-like routes.
- Is not exposed to the home network or LAN.

The download queue and the native resume data are stored in app-private storage. Resume data is requested periodically and on pause; the queue retains the magnet or private copy of the `.torrent` so a task can be recreated if no resume file exists. Startup restores queued tasks when the app/service starts. A hard process kill can lose work since the most recent resume-data save; this is not a guarantee of crash-proof zero-loss recovery.

Downloaded files remain in the app-specific `Downloads` directory. “Remove from queue” preserves downloaded data. Deleting data is a separate, explicit action.

### Playback and foreground service

- ExoPlayer plays the torrent through the loopback Range endpoint before the download reaches 100%.
- Direct Internet Archive video playback remains HTTPS and is offered only when the source metadata contains a recognized Creative Commons / public-domain license URL. Review the source item's actual terms.
- Playback position is stored locally every few seconds; a torrent position is keyed by infohash and file index, and a direct stream position by a non-reversible URL hash. Positions at or beyond about 90% of a known duration are cleared.
- Audio and text tracks can be selected from those available in the media. A matching `.srt` / `.vtt` file already fully downloaded in the same torrent can be offered as a sidecar subtitle.
- The player includes 10-second seek controls, D-pad left/right and play/pause, keeps the display awake and can hand the loopback URL to an external player.

While watching a torrent, the service uses foreground-service type `mediaPlayback`. Background downloading uses `dataSync`. Android 15+ applies a cumulative time limit to `dataSync`; when that limit is reached without active playback, Jacket saves resume data and stops the background session. **Uninterrupted all-day downloading or seeding is not promised.** Reopen Jacket to restore the queued work.

## Android TV

The same APK declares Leanback support as optional and touchscreen as not required. Android TV / Google TV has a Leanback launcher entry and banner, a navigation rail, larger poster cards, visible focus states and D-pad navigation. The player handles Back, play/pause and left/right seeking.

## Requirements and build

- Android 9 (API 28) or newer.
- Internet access for catalog metadata, remote artwork, indexers and remote media.
- TMDB Read Access Token is optional. Without it, the open Internet Archive catalog remains available.
- Build with JDK 17, Android SDK Platform 36, Build Tools 36.0.0 and Gradle 9.5.0.

```bash
./gradlew testDebugUnitTest assembleDebug
```

GitHub Actions runs unit tests and builds a debug APK on pushes to `main` and `feat/**`, and on pull requests to `main`.

## Tests in this iteration

The JVM test suite covers:
- Torznab/RSS/Atom XML parsing for magnet, `.torrent`, tracker name, seeders, peers, size and category.
- Jackett capabilities and error response handling, local-network URL policy and redirect restrictions.
- Rejection of insecure-only links and deduplication / seed sorting.
- Title-only release heuristics.
- Queue serialization and restoration independent of JNI.
- Range parsing and 200 / 206 response headers.
- Local-stream route and Host validation.

These tests do not replace manual streaming tests on physical Android 9+, Android 14/15 and Android TV devices. In particular, verify a real torrent's piece availability, container seek behavior and process recovery on hardware.

## Security and scope

- No tracker presets, credentials or piracy-site lists are embedded.
- Public indexer endpoints require HTTPS. HTTP is accepted only for explicit local/private addresses and approved local DNS suffixes; ambiguous single-label hosts and URL user-info are rejected.
- API keys, usernames/passwords and cookies are encrypted at rest; JSON exports exclude them.
- Torrent HTTP streaming binds only to loopback and is not a local-network media server.
- Jackett and TorrServer are not bundled. Jackett is an optional connection to a server managed separately by the user; the app does not need it for direct indexers or torrent playback.
- Use only sources and media you are authorized to access and use. See [SECURITY.md](SECURITY.md).
