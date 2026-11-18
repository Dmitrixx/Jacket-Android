# Changelog

## 2.1.0 — in-app torrent streaming and TV media center

- Added a private local HTTP media server bound only to `127.0.0.1`, with GET/HEAD, byte Range and 206 responses.
- Added torrent file listing, single-file priority, sequential download and elevated startup / final-piece priority.
- Added in-app magnet and `.torrent` import from the picker, Android Share and HTTPS result URLs.
- Added local Range streaming to ExoPlayer while the selected file is still downloading.
- Added queue codec, app-private metainfo copies, periodic native resume-data persistence and queue re-add fallback.
- Added foreground-service `mediaPlayback` mode during viewing and `dataSync` for background downloads.
- Added transfer speed limits, Wi-Fi-only scheduling, explicit queue removal vs downloaded-file deletion and engine errors in snapshots.
- Added play/pause, 10-second seek controls, audio/subtitle selection, matching fully downloaded SRT/VTT sidecars, external-player handoff and local playback-position persistence.
- Added parallel indexer searches (maximum four concurrent), per-provider failure reporting, per-indexer probe search, JSON import/export without secrets and title-based release filters.
- Added TMDB season / episode catalog and SxxExx episode search.
- Added bounded artwork cache and RSS/Torznab, queue, Range, route, Host and release-heuristic tests.
- Updated the single APK for phone, tablet and Android TV.

## 2.0.0 — media-center transition

- Replaced the Jackett-server connection screen with an in-app catalog and direct provider architecture.
- Added Internet Archive catalog search and metadata lookup.
- Added optional TMDB movie/series catalog, ratings, artwork and descriptions.
- Added poster cards, home shelves, Movies / Series / Search / Library navigation.
- Added item detail pages, favorites and recently opened history.
- Added direct HTTPS RSS/Atom plus Torznab/Newznab indexer registry with user-configurable API key, Basic credentials, cookie, categories, tags, Freeleech filter, sorting, manual search and test-all.
- Encrypted indexer API keys, passwords and session cookies at rest using Android Keystore AES/GCM.
- Added Android TV/Google TV launcher support, TV banner, remote-control navigation rail and focus indicators on poster cards.
- Added a Media3 / ExoPlayer playback screen for supported licensed HTTPS streams.
- Added source discovery from the detail page, while retaining the integrated libtorrent magnet queue.
- Removed the requirement for a running Jackett server and its Torznab API key.

## 1.2.0

- Added initial libtorrent4j engine, DHT, foreground service and torrent task queue.
- Added Torznab integration through a Jackett server (superseded by the in-app indexer registry in 2.0.0).

## 1.1.0

- Prepared native BitTorrent engine and service lifecycle.

## 1.0.0

- Initial Jackett-connected search prototype.
