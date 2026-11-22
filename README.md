# Jacket — Android media center 2.8.9

## Latest update APK from main

[Open the latest main-branch Android CI runs](https://github.com/Dmitrixx/Jacket-Android/actions/workflows/android.yml?query=branch%3Amain). After the newest run succeeds, download **Jacket-main-apk** from its **Artifacts** section. The ZIP contains the universal release APK and its SHA-256 checksum and is retained for 14 days. This update artifact is published only when the permanent signing key is configured; when it is absent, CI deliberately does not publish a temporary-runner debug APK that may be rejected by an existing installation.

Install the APK over the existing Jacket app. Do not uninstall the app or clear its data. Android preserves settings, torrent tasks and embedded Lampac files when the package name and signing certificate remain the same. For the one-time signing setup and update instructions, see [docs/UPDATES.md](docs/UPDATES.md).

Jacket is a single Android application for phones, tablets and Android TV / Google TV. It combines a poster-based media catalog, a user-configurable indexer cabinet, an integrated BitTorrent engine and a loopback HTTP media server.

**No separately installed Jackett or TorrServer is required.** Neither service is bundled or launched by Jacket. Direct RSS/Atom and Torznab indexers work independently; an optional connector can also query a Jackett server that the user already runs on a LAN or HTTPS endpoint. Torrent files are handled by the in-process libtorrent4j session and served to ExoPlayer through `127.0.0.1`.

The project is under active development. CI verifies JVM unit tests and APK assembly; real-device streaming, Android TV remote navigation and process-kill recovery still require hands-on acceptance testing.

## Main areas

- **Home / Movies / Series** — poster shelves and grids, ratings, years and details.
- **Search** — catalog search with a short debounce; configurable website-search shortcuts open the matching query in the external browser.
- **Series details** — TMDB seasons and episode lists; an episode search adds its `SxxExx` code to the indexer query.
- **My library** — favorites and recently opened cards.
- **Sources** — manual search, indexer management, connectivity tests, source errors and quality filters.
- **Downloads** — magnet and `.torrent` tasks, file list, selected-file priorities, pause/resume, speed and progress.
- **Player** — fullscreen Media3 / ExoPlayer for phone and Android TV: overlay controls, 10-second seek, speed, fit/fill/zoom, sleep timer, audio and subtitle selection, picture-in-picture, sidecar subtitles, external-player handoff, progressive video, HLS and DASH.
- **Settings** — LAMPA-inspired control center for Filmix device-code pairing, HDRezka account/session, built-in tracker shortcuts, torrent indexers, catalog metadata and network limits.

## Lampac integration

Start the embedded server in Settings → Lampac. When its local health check succeeds, Jacket automatically saves `http://127.0.0.1:9118`, tests `/online.js`, and enables Lampac in the unified source search. No separate manual connection step is required. Results are merged into the Sources panel for TMDB movie cards and selected TMDB series episodes. Lampac provider routes are discovered per title, with a bounded ceiling of 96 routes and four concurrent requests. The embedded `BaseModule.LoadModules` defaults to `[".*"]`, so all installed modules explicitly enabled in their own manifests can participate; the Android profile still excludes modules that need unsupported native services, external listeners or server-side transcoding.

Lampac is a backend, not a second catalog UI. The embedded profile enables the built-in JacRed API in local `jackett` mode, so Lampac's Rutor, Kinozal, NNMClub, RuTracker, Toloka, Megapeer and other configured adapters can contribute torrent results to Jacket alongside VOD streams. Direct magnets and same-origin `.torrent` download links are routed into Jacket's own torrent engine; tracker pages that need sign-in remain available to open in the user's browser; a JacRed failure does not discard successful VOD results. The settings migration enables JacRed on existing installations without replacing unrelated `init.conf` values. Settings → Lampac also has Import / Export for the existing Lampac `init.conf`: importing transfers the installed source modules' options, provider priorities, account fields, JacRed adapters and custom JSON sections into Jacket's private Lampac runtime. Import is accepted only while the embedded server is stopped. Jacket then enforces the private `127.0.0.1:9118` listener and Android-safe module exclusions; imported module choices and credentials are otherwise preserved. Reinstalling the Lampac runtime carries the previous `init.conf` forward rather than replacing it. The exported file may contain passwords and tokens—keep it private. `init.conf` transfers settings, not extra custom module binaries stored outside the config. TorrServer duplicates Jacket's in-process torrent engine, while GStreamer needs Linux native libraries not included in the lightweight Android runtime. DLNA, browser automation, server-side transcoding, Sync and TimeCode remain disabled until their device-specific networking, runtime and data-sync paths are integrated and tested. The full upstream module catalogue remains documented by [Lampac](https://docs.lampac.dev/modules/overview).

## Filmix and HDRezka accounts

- **Filmix** uses a device-code flow: Jacket requests a short-lived code, the user confirms it on the Filmix device page, and the app polls for activation. The permanent device token is encrypted using Android Keystore. No Filmix password is requested.
- **HDRezka** has HTTPS mirror, email/login, password and manual Cookie-session fields. The app attempts the normal site sign-in and saves returned cookies encrypted using Android Keystore. If a mirror presents CAPTCHA, Cloudflare or an interactive browser challenge, Jacket stops and does not attempt to bypass it; a session cookie can be entered manually after signing in through the user's regular browser.
- Credentials and cookies stay on the device and are not included in diagnostics or tracker exports.

Filmix search includes catalogue results, translations, series episodes and plan-aware quality options; expiring CDN references are refreshed by the adapter. HDRezka search uses the current DLE AJAX form request and supports only the known player payload/quality formats. Unknown or rotated encodings fail closed. HDRezka uses the user-configured HTTPS mirror; there is no automatic mirror rotation or cross-origin Cookie forwarding. These flows still require real-device/network acceptance tests.

## Built-in tracker search

The first run seeds search shortcuts for RuTracker, NNM-Club, Rutor, Kinozal, 1337x, The Pirate Bay and Internet Archive. Jacket includes in-app HTML result parsers for RuTracker, NNM-Club, Rutor, Kinozal, 1337x and The Pirate Bay. Matching magnets and direct .torrent links are shown in the native release list and can be queued or opened in the built-in player. Internet Archive uses its structured metadata API.

Tracker pages can change markup or require an account, CAPTCHA, Cloudflare or another anti-bot challenge. User-supplied Cookie sessions are stored encrypted and sent only to the configured tracker origin. Jacket does not solve challenges or bypass access controls. Other user-added website shortcuts remain ordinary browser links.

## Video source catalogue

The source picker keeps the requested priority order: HDRezka, Filmix, ColdFilm, Baskino, Kinogo, VideoCDN, Collaps, Kinobase, CDNMovies and Lordfilm. HDRezka and Filmix have direct adapters. The other eight entries are routed through the optional Lampac bridge: Jacket discovers providers enabled on the user's server at runtime, queries up to 96 returned routes with four concurrent workers, and labels each result with the real provider name. A card marked "Lampac / plugin" means a compatible module must be installed and enabled on the server; not every named site is present on every Lampac installation.

## Indexer cabinet and optional Jackett connector

Add user-owned endpoints:

- RSS / Atom feeds and Torznab / Newznab-compatible endpoints over HTTPS.
- HTTP endpoints only when they target a private / loopback / link-local IP or a local DNS suffix such as `.local` or `.lan`; this is intended for a Jackett server on your own network.
- An optional Jackett setup card with server address, API key, tracker filter (`all` by default), connection test and disconnect. Jacket probes Jackett with `t=caps`, then searches its selected tracker set. Jackett remains external and is never installed or bundled.
- Torrent session controls include download/upload rate caps, Wi-Fi-only queue gating and a maximum peer-connection setting. Automatic mode uses conservative device-aware defaults (80 for low-RAM devices, 200 otherwise); a manual cap can be set from 20 to 500.
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

Direct RSS / Atom and Torznab / Newznab indexer cards expose test, trial search, edit and delete actions from the same three-dot menu. Jackett's live catalogue uses the menu for per-tracker search, testing, configuration and the source website when available.

The built-in tracker list contains RuTracker, NNM-Club, Rutor, Kinozal, 1337x and The Pirate Bay. Each profile can be enabled/disabled, edited (HTTPS search template and optional same-origin Cookie), and tested from Settings with a real in-app search; the test reports connection/parser errors separately from an empty result set. A three-dot action menu keeps test/open, edit and delete actions together on every site card. Internet Archive and user-added website shortcuts remain browser links. The first launch adds missing default profiles. This update performs a one-time migration that re-enables existing built-in profiles that were switched off, while preserving user-edited names, saved Cookies and user-added sites. After migration, manual enable/disable choices and deletions stay effective; the explicit restore action adds missing defaults and re-enables built-in presets.

For a broader, server-maintained catalogue, connect your own Jackett endpoint: Jacket reads the indexer list from that server instead of hardcoding a snapshot. Filter public/private providers, language and configuration status, then open the selected indexer's dynamic auth fields and save them back to Jackett. This follows the centralised, parallel-search and per-private-tracker configuration approach described in the Habr overview, while keeping Jackett optional and external.

Filmix uses device-code pairing with a profile-confirmed activation flow and its current Android API fingerprint; HDRezka uses its direct adapter. The optional Filmix HTTPS relay is disabled by default because its operator can see the decoded device token and profile requests; configure only a server you operate or trust. The eight additional video catalogue entries use Lampac's live provider catalogue when a matching module is installed and enabled on the configured server. The current bridge resolves TMDB movie and selected-series-episode cards, so TMDB metadata configuration is required for this path. Additional direct feeds can be configured as RSS / Atom or Torznab / Newznab indexers; website-only providers can be added as HTTPS search shortcuts. The app does not ship tracker credentials. CAPTCHA, anti-bot challenges, proprietary sign-in flows and interactive 2FA are not automated. A manually entered cookie may work where the source permits it.

### Optional embedded Lampac runtime

On ARM64 Android devices, Settings → Lampac can install Debian, .NET 10 and a pinned Lampac NextGen release into Jacket's private storage and start it on demand. The runtime requires around 1.5 GB of free storage and downloads a Lampac archive of about 480 MB plus the .NET runtime. It does not require root or Termux. On first start, .NET compiles enabled provider modules; on Android this can take up to five minutes. Readiness is checked through Lampac's `/version?type=hash` health endpoint. The Android profile skips the optional GStreamer module: upstream initializes native GLib/GStreamer components for server-side HLS transcoding, while the embedded Android rootfs does not bundle those libraries and Jacket uses Media3 for playback. Existing installs are migrated safely: the app adds `LoadModules: [".*"]` only when the user has not set a module selection, enforces Android-specific exclusions, enables JacRed and preserves provider credentials and custom `init.conf` sections. Settings → Lampac provides a file-picker import/export for the full `init.conf`; the importer forces the loopback listener and keeps unsupported Android modules excluded. The settings screen retains the last 24 sanitized process-output lines in a scrollable panel. The server binds only to 127.0.0.1:9118; it is not exposed to the LAN, does not auto-start after reboot, and browser automation and heavy transcoding remain disabled. The embedded server runs as a foreground service only after the user presses Install/Start. Non-ARM64 devices can still use a remote Lampac endpoint over HTTPS. See [the embedded runtime notes](docs/LAMPAC-ANDROID-EMBEDDING.md).

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
- The player is fullscreen. Overlay controls hide during playback. D-pad left/right seeks 10 seconds while the overlay is hidden; Back dismisses the overlay before leaving the player. Speed (0.5×–2×) and fit / fill / zoom are remembered on the device. A sleep timer can stop playback after 15, 30, 45 or 60 minutes. Phones can enter picture-in-picture. The screen stays awake and can hand the loopback URL to an external player.

While watching a torrent, the service uses foreground-service type `mediaPlayback`. Background downloading uses `dataSync`. Android 15+ applies a cumulative time limit to `dataSync`; when that limit is reached without active playback, Jacket saves resume data and stops the background session. **Uninterrupted all-day downloading or seeding is not promised.** Reopen Jacket to restore the queued work.

## Android TV

The same APK declares Leanback support as optional and touchscreen as not required. Android TV / Google TV has a Leanback launcher entry and banner, a navigation rail, larger poster cards, visible focus states and D-pad navigation. The fullscreen player handles Back, play/pause and left/right seeking without requiring a touchscreen.

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
- Player chrome: speed cycle, sleep timer, picture fit and clock formatting.

These tests do not replace manual streaming tests on physical Android 9+, Android 14/15 and Android TV devices. In particular, verify a real torrent's piece availability, container seek behavior and process recovery on hardware.

## Security and scope

- No tracker credentials or private account cookies are embedded. Public tracker parser profiles contain no account secrets.
- Public indexer endpoints require HTTPS. HTTP is accepted only for explicit local/private addresses and approved local DNS suffixes; ambiguous single-label hosts and URL user-info are rejected.
- API keys, usernames/passwords and cookies are encrypted at rest; JSON exports exclude them.
- Torrent HTTP streaming binds only to loopback and is not a local-network media server.
- Jackett and TorrServer are not bundled. Jackett is an optional connection to a server managed separately by the user; the app does not need it for direct indexers or torrent playback.
- Use only sources and media you are authorized to access and use. See [SECURITY.md](SECURITY.md).
