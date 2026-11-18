# Jacket 2.1.0 — architecture

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
        │ await requested byte range
        ▼
   TorrentEngine (one libtorrent4j SessionManager)
        ├── magnet / private .torrent metainfo
        ├── file priorities + sequential mode
        ├── DHT and download/upload rate limits
        ├── resume-data / error snapshots
        └── download files in app-specific storage

Detail card → IndexerRegistry → magnet / .torrent → TorrentEngine → LocalStreamServer → ExoPlayer
```

No external Jackett or TorrServer process is started or packaged.

## Catalog flow

- `MediaCatalogClient` reads Internet Archive catalog metadata directly.
- Optional TMDB bearer token supplies movie/series metadata, artwork, season lists and episode lists. TMDB is a metadata provider, not a video source.
- For an episode, the query builder combines the series title, the `SxxExx` code and episode title.
- Internet Archive media playback/download actions are offered only when item metadata carries a recognized Creative Commons / public-domain license URL. This is a conservative gate, not a full license audit.
- `PosterDiskCache` keeps bounded public artwork in memory and app cache; cache files contain no credentials. It uses reference-counted per-URL locks, follows up to three HTTPS-only redirects and downsamples oversized source images before bitmap decoding.

## Indexer cabinet

`IndexerConfig` contains protocol, HTTPS template, API key, Basic credentials, cookie, categories, tags, Freeleech and sort settings.

- `IndexerRegistry.searchDetailed` launches enabled sources through a fixed pool of four workers.
- Every provider failure is isolated as an `IndexerFailure`; other responses are still returned.
- Each provider request is limited to 30 seconds wall time; a whole federated search or test-all run is limited to 60 seconds.
- Error reporting strips configured credentials, Basic auth, cookie headers and URL query values before showing errors in the UI.
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
- Selecting a video file sets its priority to TOP, all other files to IGNORE, enables sequential download and gives initial and final pieces elevated priority.
- `LocalStreamServer` listens only on `127.0.0.1`, probes ports 8090–8099, and validates Host and route.
- Every stream URL includes a random 128-bit per-server token verified with a constant-time comparison.
- Request-line and header sizes are bounded, worker backlog is capped, and closing the server closes active client sockets.
- Range responses are constructed by a pure `RangeResponseBuilder`; malformed and unsatisfiable ranges are rejected.
- Before reading each chunk, the server asks the engine to wait for every intersecting piece, bounded to about 25 seconds per piece. It then reads the byte interval from the selected file.
- Matching sidecar subtitles are exposed only when their files are already fully downloaded. Serving a subtitle never changes the selected video file's priority.
- The stream server never binds a LAN interface.

### Queue and recovery

- Queue serialization uses `TorrentQueueCodec`, independent from the native library. Older JSON queue records are migrated.
- Queue metadata includes ID, title, magnet or private `.torrent` path, and selected file index.
- Native fast-resume data is written under `filesDir/torrent-resume` using `AtomicFile` and a versioned CRC envelope; legacy raw resume files remain readable.
- Restore checks the native parser error code and verifies that the saved infohash matches the queue item. A damaged checkpoint is discarded and the retained magnet or private metainfo is used to re-add the task.
- Resume data is requested periodically (about every 30 seconds) and after pause. Orderly shutdown waits for native save-resume alert acknowledgements with a bounded timeout.
- The service restores queued items on startup and retries pending tasks. Playback positions are persisted locally at short intervals and keyed by torrent hash/file or URL hash.
- “Remove from queue” does not delete downloaded files. File deletion is explicit and path-checked.

Resume data is asynchronous and periodic: abruptly killing the process can lose the most recent interval of torrent state. Device/network behavior, process-kill recovery, real tracker/torrent formats and Android TV remote interaction still require hardware testing.

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

The JVM suite includes XML fixtures for RSS/Torznab, release-name heuristics, queue codec round-trips, Range parsing / response headers and route / Host policy checks. JNI torrent streaming, actual file-piece availability, Android foreground-service behavior and TV remote navigation require device-level acceptance tests.
