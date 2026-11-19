# Jacket 2.6.0 — architecture

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

## Catalog flow

- `MediaCatalogClient` reads Internet Archive catalog metadata directly.
- Optional TMDB bearer token supplies movie/series metadata, artwork, season lists and episode lists. TMDB is a metadata provider, not a video source.
- For an episode, the query builder combines the series title, the `SxxExx` code and episode title.
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

- Queue serialization uses `TorrentQueueCodec`, independent from the native library. Older JSON queue records are migrated.
- Queue metadata includes ID, title, magnet or private `.torrent` path, and selected file index.
- Native fast-resume data is written under `filesDir/torrent-resume` using `AtomicFile`.
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
- Cards have larger TV artwork and visible focus borders; the player maps D-pad left/right to 10-second seeking and center/play-pause to playback.
- Dialogs, list rows and text fields remain normal Compose focus targets and should be checked with physical remotes.

## Local storage and secrets

- TMDB token, indexer API key, Basic credentials and cookies use AES/GCM with an Android Keystore-held key.
- Exported indexer JSON deliberately excludes secrets.
- Queue/resume files, poster cache, playback positions, favorites and history remain in app-private storage.
- The loopback HTTP endpoint allows cleartext only for `127.0.0.1`; the endpoint is not offered as a network file server.

## Automated tests

The JVM suite includes XML fixtures for RSS/Torznab, release-name heuristics, queue codec round-trips, Range parsing / response headers, byte-range-to-piece planning, forward-window limits, disk-cache LRU eviction, overlapping-range cache hits and route / Host policy checks. JNI torrent streaming, actual file-piece availability, Android foreground-service behavior and TV remote navigation require device-level acceptance tests.
