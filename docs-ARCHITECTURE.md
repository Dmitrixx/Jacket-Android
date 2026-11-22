# Jacket 2.8.7 — architecture

## Runtime layout

### Optional embedded Lampac runtime
- After the local service passes its health probe, the settings UI persists `http://127.0.0.1:9118` and enables the Lampac group in combined source search. Available `/lite/events` routes are discovered for each TMDB movie/episode; up to 96 provider routes are considered with four concurrent requests.
- Embedded configuration loads JacRed in `typesearch: "jackett"` mode while keeping its API bound to loopback. The app queries `/api/v2.0/indexers/all/results`; the embedded config enables Megapeer (the one upstream adapter disabled by default) unless the user explicitly disabled it; direct magnets and same-origin `.torrent` download URLs are passed to the in-process torrent engine; `parseMagnet` routes are not invoked automatically because upstream adapters may attempt credentialed resolution. Existing runtime configurations are migrated without overwriting unrelated options.
- ARM64 only: `:proot-engine` is built from the pinned `oonid/pr` submodule and its native helper binaries are extracted into `nativeLibraryDir` for Android's W^X execution policy. The app still builds for other ABIs; embedded mode is disabled there and the existing remote HTTPS bridge remains available.
- On explicit user action, `LampacRuntimeService` installs a pinned Debian rootfs, .NET 10 and Lampac NextGen under app-private `filesDir`. Artifact downloads use a strict HTTPS host allowlist and pinned SHA-256/SHA-512 digests; extraction is size-bounded and validated before activation.
- The local `init.conf` binds to `127.0.0.1:9118`. Browser automation, heavy transcoding and public listener modules are disabled. The optional `GStreamer` module is also skipped: its upstream initializer calls native GLib/GStreamer APIs, but those libraries are not included in the minimal Android Debian rootfs and the app uses Media3 for playback. Existing runtime installations are migrated before every launch by adding only this module to `BaseModule.SkipModules`, preserving all other `init.conf` settings. No boot receiver or automatic server restart is registered.
- The server is owned by a foreground `specialUse` service with visible status/progress and a Stop action. First startup compiles enabled provider modules and is allowed up to five minutes; the watchdog probes Lampac's documented `/version?type=hash` health endpoint every 500 ms. A timeout is reported as a startup failure; the service intentionally stops the child only after the deadline. When startup fails, the settings log panel shows the last 24 sanitized output lines, including the leading exception and any inner socket error. It requires around 1.5 GB free storage and downloads approximately 480 MB for the Lampac archive plus the .NET runtime. Embedded installation and startup must still be tested on physical ARM64 Android hardware.

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
        ├── 8 MiB / 32-piece forward playback window + initial tail-piece request
        ├── piece-finished condition signal (no timed polling loop)
        ├── DHT and download/upload rate limits
        ├── resume-data / error snapshots
        └── verified download files in app-specific storage

Detail card → IndexerRegistry → magnet / .torrent → TorrentEngine → LocalStreamServer → ExoPlayer
```

No external Jackett or TorrServer process is started or packaged.

## Direct video sources

- Filmix uses an encrypted device token and a single active HTTPS API origin. AUTO probes the explicitly saved user-owned HTTPS API first (when present), then the published primary and reserve hosts; it sends the installation ID/token only after the selected origin returns a recognized Filmix API response and retains the successful origin for activation polling and catalog requests. Explicit custom hosts require HTTPS, a DNS hostname (not an IP literal or local suffix), the standard HTTPS port and the `/api/v2` path; normal certificate validation remains enabled. Hostname validation does not pin DNS resolutions, so a user-supplied hostname that changes its DNS answer remains a trust boundary.
- HDRezka uses only the mirror explicitly configured by the user. The client parses the DLE AJAX search response, `post_id`, `initCDN*Events`, translator flags, favorites token and series defaults; AJAX requests use movie flags or resolved season/episode IDs. It decodes the known HDRezka `#h` stream payload locally and resolves quality fallbacks; unknown/rotated encodings fail closed. DLE cookies are sent only to the configured mirror, never to media CDNs. CAPTCHA/Anubis, paywalls, Filmix `#2` manifests and cross-origin redirects are not bypassed.
- Media3 receives only non-secret `Referer`/`User-Agent` headers and parsed subtitle configurations. Account cookies are never copied into player request headers.
- `io.put:media3-ffmpeg-decoder:1.11.1+1` is version-matched to Media3 1.11.1 and loaded through `DefaultRenderersFactory` with extension renderers preferred. Its LGPL-only FFmpeg build enables AC-3, E-AC-3, DTS, MLP and TrueHD (as well as the additional decoders listed by the upstream artifact); it does not enable GPL or nonfree components. The AAR provides four ABIs and 16 KB alignment for 64-bit libraries. FFmpeg is statically linked into `libffmpegJNI.so`, so the matching `ffmpeg-source` archive and rebuild/relink instructions are distributed alongside the dependency.
- The app embeds the upstream Apache, LGPL and FFmpeg third-party notices in `app/src/main/assets/licenses/ffmpeg/` and exposes them from Settings → About. Gradle packaging deliberately keeps `META-INF/media3-ffmpeg-decoder/**` resources. Codec patent rights are separate from the software licences.
- The detail-card source picker merges torrents, Filmix, HDRezka and license-gated Internet Archive results, isolates individual provider failures and displays source type/quality/translation metadata.

## Video source registry

`MediaSourceCatalog` preserves the ten requested provider IDs, priorities, media types, auth requirements and 4K flags. Filmix and HDRezka now have direct adapters: Filmix uses its selected HTTPS API and device token; HDRezka uses only the mirror explicitly entered by the user. The other eight descriptors are managed through the optional Lampac catalogue when a matching module is installed and enabled on the user's server. Jacket discovers runtime routes, checks up to 48 with four concurrent workers and isolates per-provider failures. Availability still depends on server modules, network access, account entitlements and the provider's stream format.

## Catalog flow

- `MediaCatalogClient` reads Internet Archive catalog metadata directly.
- Optional TMDB bearer token supplies movie/series metadata, artwork, season lists and episode lists. TMDB is a metadata provider, not a video source.
- Torrent indexer searches include the `SxxExx` code and episode title. Filmix and HDRezka search the series title/year, then resolve the selected season/episode through the provider's own catalogue/player response.
- Internet Archive media playback/download actions are offered only when item metadata carries a recognized Creative Commons / public-domain license URL. This is a conservative gate, not a full license audit.
- `PosterDiskCache` keeps bounded public artwork in memory and app cache; cache files contain no credentials.

## Filmix device authorization
- Pairing uses a per-install device ID and the Lampa-compatible device fingerprint. The activation token is accepted only after the selected Filmix API reports user profile data.
- API selection is pinned after pairing. The optional HTTPS relay is empty by default; it is a user-managed trust decision because the relay operator can inspect the decoded request payload, including the device token.

## Lampac provider federation
- Lampac routes are discovered from the configured server through `/lite/events` and `/lifeevents`; the Android app does not hard-code a snapshot of provider plugins.
- Up to 48 routes are queried using a four-worker pool. Each route failure is returned as a separate `IndexerFailure`; successful provider results are retained.
- The provider slug/name is preserved in `SourceResult`, while account parameters are stripped from page metadata. Provider and stream requests remain pinned to the configured Lampac origin.
- The current bridge accepts TMDB movie and selected-series-episode cards. TMDB metadata credentials and a running, configured Lampac instance are prerequisites. A catalogue label does not assert that every named plugin exists on the server.

## Indexer cabinet

`IndexerConfig` contains protocol, HTTPS template, API key, Basic credentials, cookie, categories, tags, Freeleech and sort settings.

- `IndexerRegistry.searchDetailed` launches enabled sources through a fixed pool of four workers.
- Every provider failure is isolated as an `IndexerFailure`; other responses are still returned.
- RSS/Atom and Torznab/Newznab are parsed into provider-neutral `SourceResult` values.
- Common Torznab attributes are parsed: `magneturl`, `seeders`, `peers`, `size` / `length`, `category`.
- Results are deduplicated by magnet, download URL, stream URL, then provider result ID; sorting is seeders descending, then release title.
- Per-indexer test and probe search are separate operations. Test-all also runs with a four-worker cap.
- Quality, codec, HDR/DV and audio/dubbing tags are title-only heuristics. Client filters do not repeat the network request.
- `ReleaseHeuristics` also derives resolution dimensions, audio channel count, recognizable language/subtitle markers and bitrate from release titles. It labels CAMRip/TeleSync/TeleCine/SCR, 10-bit/Hi10P, HLG, HDR10+ and known dubbing studios, and marks screen copies for the optional detail-card filter. These labels are best-effort title heuristics, not verified track metadata.
- `IndexerRegistry` preserves Torznab/RSS publication dates for release cards when a feed provides `pubDate` / `published`.
- `JackettIndexerClient` uses Jackett's native REST endpoints: `GET /api/v2.0/indexers` for the public/private/configured catalogue, `GET /api/v2.0/indexers/{id}/config` for dynamic setup fields and `POST` to the same config route to save them. The API key is retained for Torznab queries; a separate optional dashboard session Cookie is used for authenticated configuration calls. The Cookie may be pasted manually or obtained by posting the administrator password to the selected server's `/UI/Dashboard` controller; the password is held only for the login request and is not persisted. Only the `Jackett=` response cookie is extracted, encrypted by `MediaSettingsStore`, and sent only to the selected Jackett origin. Login redirects are not followed. Setup fields cover text/password, booleans, selects, checkboxes, tags and safe text-only information fields. Per-indexer searches use the existing Torznab parser; `.torrent` downloads are queued in Jacket.
- `.torrent` download actions from the indexer cabinet enqueue the file in Jacket's foreground torrent service. Release results remain native `SourceCard` items and do not navigate to the browser to display the list.
- JSON import/export includes non-secret configuration only. Credentials must be entered again after import.

The direct RSS/Torznab indexer registry intentionally remains separate from the six built-in HTML tracker profiles (RuTracker, NNM-Club, Rutor, Kinozal, 1337x and The Pirate Bay). The first settings read seeds missing default profiles once. This update also performs a one-time migration that enables existing built-in profiles without changing their names or encrypted Cookies, and without re-adding profiles the user previously deleted. User-added sites and their enabled states are preserved. Once the migration flag is stored, subsequent user toggles are respected. A deliberate restore action can add missing defaults and re-enable built-in presets. Built-in profiles can be enabled, disabled, edited and tested from Settings; a test runs the real parser and reports provider-specific errors. The optional Jackett catalogue remains the scalable path for a wider set of public/private providers and dynamically exposes each indexer's own configuration fields. The registry does not solve CAPTCHA, anti-bot checks or interactive 2FA. A user may supply a session cookie if the indexer's rules allow it.

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
- `StreamPiecePlanner` maps an HTTP Range start/length to torrent piece indices using `fileOffset` and `pieceLength`. The current piece gets the closest deadline; following pieces receive increasing deadlines in an 8 MiB / 32-piece bounded window. The initial byte-zero request may also schedule the file's final piece after the forward window to help containers whose index is stored at the tail. A new seek replaces the previous window.
- This is deadline-based piece scheduling, not a hard guarantee that every peer will deliver pieces in strict order: peer availability and network conditions still apply. It does, however, prioritize the currently requested playback position ahead of the forward buffer.
- `LocalStreamServer` listens only on `127.0.0.1`, probes ports 8090–8099, and validates Host and route.
- Range responses are constructed by a pure `RangeResponseBuilder`; malformed and unsatisfiable ranges are rejected.
- The server waits for the exact requested byte interval's pieces to be hash-checked and written to disk before reading that interval. It never requires the whole file to finish first. Waiting is signaled by libtorrent `PIECE_FINISHED` alerts and a generation-checked condition, rather than a 120 ms polling sleep; pause, removal and session shutdown wake waiters immediately. Shutdown invalidates active stream-window leases before broadcasting and rechecks the stopping state immediately before parking, preventing a worker from missing the final shutdown notification and sleeping until its piece timeout. When a seek replaces the active priority window, its identity token invalidates older Range workers and wakes them so they fail fast instead of blocking on pieces from an obsolete position.
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

- The libtorrent session caps connections at 80 on Android devices that report low RAM and 200 otherwise.
- Idle/background transfer uses `dataSync`.
- Active in-app torrent playback switches the foreground service to `mediaPlayback`.
- Android 15+ can time-limit `dataSync`. If the time limit arrives with no active playback, the service saves resume data and stops rather than claiming infinite background seeding.
- On Android 12+, the player requests the source video's frame rate on its `SurfaceView` only while playback is actively rendering, using fixed-source compatibility and the long-form `CHANGE_FRAME_RATE_ALWAYS` strategy. Media3's own frame-rate calls are disabled to avoid competing requests; the request is reset on pause/stop and when the view is detached.
- Audio extension renderers are preferred so the matching LGPL FFmpeg AAR can decode supported tracks when Android's platform `MediaCodec` cannot. Devices before API 31 keep the system-selected rate.
- The player releases ExoPlayer and stores position when its screen is left; the download service and torrent session remain alive unless Android stops them or the user removes/stops the task.

## UI and device profiles

- One application module and one APK for phone, tablet and Android TV.
- Leanback launcher category and banner are provided; touchscreen is optional.
- Phone uses bottom navigation; television uses a navigation rail.
- Site, tracker, direct-indexer, media-provider and Jackett catalogue cards use a common three-dot action menu. The available actions are scoped to each adapter: native test/search, external website, configure, edit or delete.
- Torrent network settings include download/upload rate caps, Wi-Fi-only queue gating and a configurable peer connection ceiling. Automatic mode keeps device-aware defaults (80 connections on low-RAM devices, 200 otherwise); manual values are bounded to 20–500.
- Cards have larger TV artwork and visible focus borders. The player is fullscreen on phone and television: overlay controls auto-hide, D-pad left/right seeks 10 seconds while the chrome is hidden, and Back dismisses the chrome before exiting. Speed and picture-fit choices are stored in app-private preferences. Phones can enter picture-in-picture; the activity handles the corresponding configuration changes so playback is not recreated.
- Dialogs, list rows, overflow menus and text fields remain normal Compose focus targets and should be checked with physical remotes.

## Local storage and secrets

- TMDB token, indexer API key, Basic credentials and cookies use AES/GCM with an Android Keystore-held key.
- Exported indexer JSON deliberately excludes secrets.
- Queue/resume files, poster cache, playback positions, favorites and history remain in app-private storage.
- Playback positions are cleared when the player saves 0 ms (explicit restart from the beginning); the 90% watched threshold remains overflow-safe.
- The loopback HTTP endpoint allows cleartext only for `127.0.0.1`; the endpoint is not offered as a network file server.

## Automated tests

The JVM suite includes XML fixtures for RSS/Torznab, release-name heuristics, queue codec round-trips, Range parsing / response headers, byte-range-to-piece planning, forward-window and tail-prefetch deadlines, piece-completion signaling, frame-rate normalization, disk-cache LRU eviction, overlapping-range cache hits and route / Host policy checks. JNI torrent streaming, actual file-piece availability, Android foreground-service behavior and TV remote navigation require device-level acceptance tests.


## Consolidated source, stream and navigation safeguards

- Built-in HTML tracker fetches validate the initial HTTPS URL, each manual redirect and every resolved detail/download URL. Cookies are attached only to the original tracker host. This hostname policy does not resolve and pin DNS addresses, so DNS rebinding remains a limitation.
- IPv4 and IPv6 loopback, private, link-local, multicast and documentation ranges are rejected for public tracker requests; user-managed local Jackett endpoints continue to use their separate local-network policy.
- Detail-card source groups (torrent indexers/Internet Archive, built-in HTML trackers, Filmix, HDRezka and Lampac) execute concurrently and publish partial snapshots as each provider finishes. Built-in trackers retain their own completion-order updates and 25-second deadline; the full group has a shared 60-second deadline. A newer search generation invalidates stale result and loading-state callbacks, and publication is closed after finalization so late cancelled workers cannot overwrite the completed snapshot.
- The loopback media server primes the first requested byte segment before sending successful 200/206 headers. A temporarily unavailable first piece produces a sanitized retryable 503 with Retry-After.
- Compose saveable-state providers retain the main shell, detail/settings/download/player screens and each home/movies/series/search/library tab. Physical Android TV remote acceptance remains required.
- Filmix and HDRezka integrations use HTTPS and supported request/response formats. Unsupported or rotated player payloads fail closed. No CAPTCHA, Cloudflare/Anubis, DRM, paywall or regional restriction bypass is implemented.
