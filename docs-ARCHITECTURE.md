# Jacket 2.7.0 — architecture

## Runtime layout

```text
Compose UI (phone / tablet / Android TV)
  ├── Catalog shelves, card details, TMDB seasons and episodes
  ├── Indexer cabinet and manual search
  ├── Downloads → torrent file list → select one file
  └── ExoPlayer
        │ HTTP GET/HEAD + Range
        ▼
   LocalStreamServer (127.0.0.1:8090..8099)
        │ exact HTTP byte ranges + bounded disk LRU ring
        ▼
   TorrentEngine (one libtorrent4j SessionManager)
        ├── magnet / private .torrent metainfo
        ├── file priorities + range-driven piece deadlines
        ├── 8 MiB / 32-piece forward playback window
        ├── DHT and download/upload rate limits
        ├── resume-data / error snapshots
        └── verified download files in app-specific storage

Detail card → IndexerRegistry → magnet / .torrent → TorrentEngine → LocalStreamServer → ExoPlayer
```

No external Jackett or TorrServer process is started or packaged.

## Direct video sources

- Filmix uses the explicitly selected allowlisted HTTPS API host and encrypted device token. Search, post details, account profile and plan-limited quality variants are resolved through Filmix v2.
- HDRezka uses only the mirror explicitly configured by the user. The client parses the DLE AJAX search response, `post_id`, `initCDN*Events`, translator flags, favorites token and series defaults; AJAX requests use movie flags or resolved season/episode IDs. It decodes the known HDRezka `#h` stream payload locally and resolves quality fallbacks; unknown/rotated encodings fail closed. DLE cookies are sent only to the configured mirror, never to media CDNs. CAPTCHA/Anubis, paywalls, Filmix `#2` manifests and cross-origin redirects are not bypassed.
- Media3 receives only non-secret `Referer`/`User-Agent` headers and parsed subtitle configurations. Account cookies are never copied into player request headers.
- The detail-card source picker merges torrents, Filmix, HDRezka and license-gated Internet Archive results, isolates individual provider failures and displays source type/quality/translation metadata.

## Video source registry

`MediaSourceCatalog` preserves the ten requested provider IDs, priorities, media types, auth requirements and 4K flags. Filmix and HDRezka now have direct adapters: Filmix uses its selected HTTPS API and device token; HDRezka uses only the mirror explicitly entered by the user. The other eight descriptors remain catalog metadata with no active adapter. Provider availability still depends on the user's network, account, source mirror and the site's supported stream format.

## Catalog flow

- `MediaCatalogClient` reads Internet Archive catalog metadata directly.
- Optional TMDB bearer token supplies movie/series metadata, artwork, season lists and episode lists. TMDB is a metadata provider, not a video source.
- Torrent indexer searches include the `SxxExx` code and episode title. Filmix and HDRezka search the series title/year, then resolve the selected season/episode through the provider's own catalogue/player response.
- Internet Archive media playback/download actions are offered only when item metadata carries a recognized Creative Commons / public-domain license URL. This is a conservative gate, not a full license audit.
- `PosterDiskCache` keeps bounded public artwork in memory and app cache; cache files contain no credentials.

## Indexer cabinet

`IndexerConfig` contains protocol, HTTPS template, API key, Basic credentials, cookie, categories, tags, Freeleech and sort settings.

- `IndexerRegistry.searchDetailed` launches enabled sources through a fixed pool of four workers.
- Every provider failure is isolated as an `IndexerFailure`; other responses are still returned.
- RSS/Atom and Torznab/Newznab are parsed into provider-neutral `SourceResult` values.
- Common Torznab attributes are parsed: `magneturl`, `seeders`, `peers`, `size` / `length`, `category`.
- Results are deduplicated by magnet, download URL, stream URL, then provider result ID; sorting is seeders descending, then release title.
- Per-indexer test and probe search are separate operations. Test-all also runs with a four-worker cap.
- Quality, codec, HDR/DV and audio/dubbing tags are title-only heuristics. Client filters do not repeat the network request.
- `ReleaseHeuristics` also derives resolution dimensions, audio channel count, recognizable language/subtitle markers and bitrate from release titles. These labels are best-effort heuristics, not verified track metadata.
- `IndexerRegistry` preserves Torznab/RSS publication dates for release cards when a feed provides `pubDate` / `published`.
- `JackettIndexerClient` uses Jackett's native REST endpoints: `GET /api/v2.0/indexers` for the public/private/configured catalogue, `GET /api/v2.0/indexers/{id}/config` for dynamic setup fields and `POST` to the same config route to save them. The API key is retained for Torznab queries; a separate optional dashboard session Cookie is used for authenticated configuration calls. The Cookie is encrypted by `MediaSettingsStore` and sent only to the selected Jackett origin. Setup fields cover text/password, booleans, selects, checkboxes, tags and safe text-only information fields. Per-indexer searches use the existing Torznab parser; `.torrent` downloads are queued in Jacket.
- `.torrent` download actions from the indexer cabinet enqueue the file in Jacket's foreground torrent service. Release results remain native `SourceCard` items and do not navigate to the browser to display the list.
- JSON import/export includes non-secret configuration only. Credentials must be entered again after import.

The registry intentionally does not include tracker-specific HTML parsers or solve CAPTCHA, anti-bot checks or interactive 2FA. A user may supply a session cookie if the indexer's rules allow it.

## Torrent engine

`TorrentEngine` is the sole owner of JNI handles and the native session. UI code receives immutable snapshots:

- queue ID, name, overall progress and byte counts;
- transfer rates, seeds / peers, state and engine error;
- immutable file entries with index, path, size, downloaded bytes, progress, priority and selected marker;
- selected-file progress and the active loopback port.

### Add / select / stream

- Magnet links and imported `.torrent` files are stored in the queue. The metainfo is copied into app-private `filesDir/torrent-metainfo`.
- Once metadata exists, UI can inspect files.
- Selecting a video file sets its priority to TOP and all other files to IGNORE. Global sequential mode is disabled for playback because it would continue from the beginning after a seek.
- `StreamPiecePlanner` maps an HTTP Range start/length to torrent piece indices using `fileOffset` and `pieceLength`. The current piece gets the closest deadline; following pieces receive increasing deadlines in an 8 MiB / 32-piece bounded window. A new seek replaces the previous window.
- This is deadline-based piece scheduling, not a hard guarantee that every peer will deliver pieces in strict order: peer availability and network conditions still apply. It does, however, prioritize the currently requested playback position ahead of the forward buffer.
- `LocalStreamServer` listens only on `127.0.0.1`, probes ports 8090–8099, and validates Host and route.
- Range responses are constructed by a pure `RangeResponseBuilder`; malformed and unsatisfiable ranges are rejected.
- The server waits for the exact requested byte interval's pieces to be hash-checked and written to disk before reading that interval. It never requires the whole file to finish first.
- `StreamDiskCache` stores recent byte intervals in a 32 MiB LRU disk ring (maximum 256 KiB per segment). A seek that overlaps a cached interval can read the cached suffix; old slots are evicted as new segments arrive.
- Cache reads allocate at most one segment per active HTTP worker. The cache file is temporary and deleted when the local server closes.
- The disk ring stores disposable playback copies only. Verified torrent data is never removed by cache eviction, preserving hash-check state, seeding data and fast-resume consistency.
- Matching sidecar subtitles are exposed only when their files are already fully downloaded. Serving a subtitle never changes the selected video file's priority.
- The stream server never binds a LAN interface.

### Queue and recovery

- Queue serialization uses `TorrentQueueCodec`, independent from the native library. V2 decoding skips malformed individual rows instead of losing the whole queue; legacy JSON migration is bounded, derives magnet IDs from their URI, validates metainfo paths, and deduplicates canonical IDs before writing V2. Encoded queue data is capped at 8 MiB.
- Queue metadata includes ID, title, magnet or private `.torrent` path, and selected file index. Queue and pause-state mutations use synchronous SharedPreferences commits on the service IO dispatcher before native session mutations are acknowledged.
- Native fast-resume data is written under `filesDir/torrent-resume` using `AtomicFile`. Restore uses `AtomicFile.openRead()` (not direct `File.readBytes()`) so an interrupted write rolls back to the last committed backup. Checkpoint reads, writes and deletes are serialized on the engine monitor; oversized or invalid snapshots are removed together with stale backups, then the durable queue falls back to the saved metainfo or magnet URI.
- Resume data is requested periodically (about every 30 seconds) and after pause; queue entries provide a re-add fallback if resume data is not yet present.
- The service restores queued items on startup and retries pending tasks. Playback positions are persisted locally at short intervals and keyed by torrent hash/file or URL hash.
- “Remove from queue” does not delete downloaded files. File deletion is explicit and path-checked.

Resume data is asynchronous and periodic: abruptly killing the process can lose the most recent interval of torrent state. Device/network behavior and real tracker/torrent formats still require hardware testing.

## Foreground-service lifecycle

- Idle/background transfer uses `dataSync`.
- Active in-app torrent playback switches the foreground service to `mediaPlayback`.
- Android 15+ can time-limit `dataSync`. If the time limit arrives with no active playback, the service saves resume data and stops rather than claiming infinite background seeding.
- The player releases ExoPlayer and stores position when its screen is left; the download service and torrent session remain alive unless Android stops them or the user removes/stops the task.

## UI and device profiles

- One application module and one APK for phone, tablet and Android TV.
- Leanback launcher category and banner are provided; touchscreen is optional.
- Phone uses bottom navigation; television uses a navigation rail.
- Cards have larger TV artwork and visible focus borders. The player is fullscreen on phone and television: overlay controls auto-hide, D-pad left/right seeks 10 seconds while the chrome is hidden, and Back dismisses the chrome before exiting. Speed and picture-fit choices are stored in app-private preferences. Phones can enter picture-in-picture; the activity handles the corresponding configuration changes so playback is not recreated.
- Dialogs, list rows and text fields remain normal Compose focus targets and should be checked with physical remotes.

## Local storage and secrets

- TMDB token, indexer API key, Basic credentials and cookies use AES/GCM with an Android Keystore-held key.
- Exported indexer JSON deliberately excludes secrets.
- Queue/resume files, poster cache, playback positions, favorites and history remain in app-private storage.
- The loopback HTTP endpoint allows cleartext only for `127.0.0.1`; the endpoint is not offered as a network file server.

## Automated tests

The JVM suite includes XML fixtures for RSS/Torznab, release-name heuristics, queue codec round-trips, Range parsing / response headers, byte-range-to-piece planning, forward-window limits, disk-cache LRU eviction, overlapping-range cache hits and route / Host policy checks. JNI torrent streaming, actual file-piece availability, Android foreground-service behavior and TV remote navigation require device-level acceptance tests.


## Consolidated source, stream and navigation safeguards

- Built-in HTML tracker fetches validate the initial HTTPS URL, each manual redirect and every resolved detail/download URL. Cookies are attached only to the original tracker host. This hostname policy does not resolve and pin DNS addresses, so DNS rebinding remains a limitation.
- IPv4 and IPv6 loopback, private, link-local, multicast and documentation ranges are rejected for public tracker requests; user-managed local Jackett endpoints continue to use their separate local-network policy.
- Tracker searches publish completed providers in completion order under one aggregate deadline. Detail-page resolution is separately bounded; a newer search generation invalidates stale result and loading-state callbacks.
- The loopback media server primes the first requested byte segment before sending successful 200/206 headers. A temporarily unavailable first piece produces a sanitized retryable 503 with Retry-After.
- Compose saveable-state providers retain the main shell, detail/settings/download/player screens and each home/movies/series/search/library tab. Physical Android TV remote acceptance remains required.
- Filmix and HDRezka integrations use HTTPS and supported request/response formats. Unsupported or rotated player payloads fail closed. No CAPTCHA, Cloudflare/Anubis, DRM, paywall or regional restriction bypass is implemented.
