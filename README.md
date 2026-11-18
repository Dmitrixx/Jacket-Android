# Jacket — Android media center 2.1.0

Jacket is a single Android application for phones, tablets and Android TV / Google TV. It combines a poster-based media catalog, a user-configurable indexer cabinet, an integrated BitTorrent engine and a loopback HTTP media server.

**No separately installed Jackett or TorrServer is required.** Neither service is bundled, launched as a second process, nor called over an external API. Indexers are queried directly by the app; torrent files are handled by the in-process libtorrent4j session and served to ExoPlayer through `127.0.0.1`.

The project is under active development. CI verifies JVM unit tests and APK assembly; real-device streaming, Android TV remote navigation and process-kill recovery still require hands-on acceptance testing.

## Main areas

- **Home / Movies / Series** — poster shelves and grids, ratings, years and details.
- **Search** — catalog search with a short debounce.
- **Series details** — TMDB seasons and episode lists; an episode search adds its `SxxExx` code to the indexer query.
- **My library** — Continue Watching for local torrent sessions, favorites and recently opened cards.
- **Sources** — manual search, indexer management, connectivity tests, source errors and quality filters.
- **Downloads** — magnet and `.torrent` tasks, file list, selected-file priorities, pause/resume, speed and progress.
- **Player** — Media3 / ExoPlayer, seek controls, audio/subtitle track selection, downloaded sidecar subtitles and external-player handoff.
- **Settings** — TMDB token, indexers, download/upload speed limits and Wi-Fi-only mode.

## Indexer cabinet — no Jackett server

Add user-owned HTTPS endpoints:

- RSS / Atom feeds.
- Torznab / Newznab-compatible search endpoints. A Jackett or Prowlarr Torznab URL can be entered as an ordinary user-configured endpoint if it is reachable and authorized; Jacket does not install either service.
- API key, HTTP Basic username/password, or a manually supplied session cookie.
- Categories, tags, Freeleech flag, sort field and sort order.
- Enable/disable, edit, individual test, test all and a per-indexer probe search.

A search runs enabled providers in parallel with at most four concurrent requests. A failed provider is reported by name and does not cancel successful peers. Feed responses are limited to 4 MiB, 100 entries per feed and 200 merged results. Each provider request has a 30-second wall-clock deadline and an entire search has a 60-second deadline; connection and socket idle timeouts are also bounded. Credential values and query-string secrets are redacted from provider errors.

Indexer settings can be exported/imported as JSON. **API keys, passwords and cookies are deliberately omitted from exports** and must be entered again after import. Locally stored secrets are encrypted with Android Keystore using AES/GCM.

Example Torznab search template:

```text
https://your-authorized-indexer.example/api?t=search&q={query}
```

The URL must use HTTPS and include `{query}`; optionally include `{apikey}` where the provider expects the key in the URL. The adapter understands common Torznab attributes including magnet URL, seeders, peers, size/length and category.

Release labels such as 720p / 1080p / 2160p, HDR / DV, HEVC / x264 and common audio/dubbing tags are extracted heuristically from the release title. They are not authoritative tracker metadata. Filters operate on the current results and do not issue another request.

The app does not ship tracker lists, private credentials or site-specific scraping presets. CAPTCHA, anti-bot challenges, proprietary sign-in flows and interactive 2FA are not automated. A manually entered cookie may work where the source permits it.

## Torrent engine and local stream server

The app uses one in-process libtorrent4j session:

1. Add a magnet or import a `.torrent` file from the app picker, Android Share, or an HTTPS result.
2. After metadata arrives, inspect the files in the torrent.
3. Select one file to watch. Jacket gives it the highest file priority, marks the other files `IGNORE`, enables sequential download and raises priority for the first pieces and the final piece.
4. ExoPlayer requests the selected file from the local HTTP server while libtorrent is still downloading the required pieces.

The local endpoint:

- Binds only to `127.0.0.1`, using port 8090–8099.
- Supports `GET`, `HEAD`, byte `Range`, `206 Partial Content`, `Content-Range`, `Accept-Ranges` and MIME types based on the filename.
- Waits a bounded interval for each requested torrent piece instead of requiring the entire file to finish first.
- Uses a random 128-bit per-server URL token and constant-time token comparison in addition to loopback Host validation.
- Bounds request-line and header sizes, caps active/queued clients, and closes active sockets when the server stops.
- Rejects malformed hashes, invalid file indices and traversal-like routes.
- Is not exposed to the home network or LAN.

The download queue and native resume data are stored in app-private storage. Resume checkpoints use an atomic file write and a versioned CRC envelope; restoration validates both libtorrent's parser result and the infohash. The service waits for save-resume alerts during orderly shutdown and falls back to the retained magnet or private metainfo if a checkpoint is missing or corrupt. The periodic checkpoint still means a hard process kill can lose progress since the last acknowledged save; this is not a guarantee of crash-proof zero-loss recovery.

Downloaded files remain in the app-specific `Downloads` directory. “Remove from queue” preserves downloaded data. Deleting data is a separate, explicit action.

### Playback and foreground service

- ExoPlayer plays the torrent through the loopback Range endpoint before the download reaches 100%.
- Direct Internet Archive video playback remains HTTPS and is offered only when the source metadata contains a recognized Creative Commons / public-domain license URL. Review the source item's actual terms.
- Playback position is stored locally every few seconds; a torrent position is keyed by infohash and file index, and a direct stream position by a non-reversible URL hash. Positions at or beyond about 90% of a known duration are cleared.
- Local torrent sessions appear in Continue Watching with their saved position; selecting an entry resumes the same torrent file. This shelf is currently torrent-session based and is not yet a full TMDB card/episode metadata join.
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
- Torznab/RSS XML parsing for magnet, `.torrent`, seeders, peers, size and category.
- Rejection of insecure-only links and deduplication / seed sorting.
- Title-only release heuristics.
- Queue serialization and restoration independent of JNI.
- Range parsing and 200 / 206 response headers.
- Local-stream route and Host validation.

These tests do not replace manual streaming tests on physical Android 9+, Android 14/15 and Android TV devices. In particular, verify a real torrent's piece availability, container seek behavior and process recovery on hardware.

## Security and scope

- No tracker presets, credentials or piracy-site lists are embedded.
- Indexer URL templates must be HTTPS and cannot contain URL user-info.
- API keys, usernames/passwords and cookies are encrypted at rest; JSON exports exclude them.
- Torrent HTTP streaming binds only to loopback and is not a local-network media server.
- No separate Jackett, Prowlarr or TorrServer process is included.
- Use only sources and media you are authorized to access and use. See [SECURITY.md](SECURITY.md).
