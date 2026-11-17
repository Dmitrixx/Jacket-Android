# Jacket architecture

## App layers

- `data/MediaModels.kt` — catalog item, detail, indexer and source result models.
- `data/MediaCatalogClient.kt` — direct Internet Archive search/metadata and optional TMDB catalog metadata.
- `data/IndexerRegistry.kt` — built-in provider federation and user-configured direct RSS/Atom indexers. No Jackett API client.
- `data/MediaSettingsStore.kt` — encrypted TMDB access token, RSS source definitions, favorites and watch history.
- `torrent/TorrentQueueStore.kt` — local magnet task queue and paused IDs.
- `torrent/TorrentEngine.kt` — one libtorrent4j session; UI receives immutable StateFlow snapshots only.
- `torrent/TorrentDownloadService.kt` — foreground service, control actions and notification.
- `ui/JacketApp.kt` — catalog shelves, grid cards, search, details, source picker, favorites, history, downloads and settings.
- `ui/RemotePoster.kt` — asynchronous poster/backdrop loading.
- `ui/JacketPlayerScreen.kt` — Media3 / ExoPlayer in-app player.
- `ui/MainViewModel.kt` — coroutine orchestration and state.

## Catalog flow

1. The home page requests provider sections.
2. Without credentials, the app browses the Internet Archive catalog directly.
3. When a user adds a TMDB Read Access Token, TMDB supplies richer movie/series metadata and artwork. TMDB is not used as a video source.
4. Selecting a card loads detail metadata. The details page can search the indexer registry for additional sources.
5. Internet Archive playback/download actions are exposed only where the item metadata contains a license URL.

## Direct indexer flow

1. A user adds an HTTPS RSS/Atom search URL template containing `{query}`.
2. The app URL-encodes the search phrase and requests the configured feed directly from the device.
3. RSS/Atom entries are parsed into a provider-neutral SourceResult model.
4. Results from user feeds and the built-in Internet Archive provider are merged, de-duplicated and sorted.
5. Magnet links can be sent to the integrated libtorrent engine; torrent-file links currently open an external handler.

The registry is an adapter boundary: adding a provider requires a new adapter and registration, not changes to the Compose screens. Site-specific HTML adapters should be implemented only for sources whose terms permit automated access, with per-host timeouts, rate limits and parser tests.

## Media playback

Media3 ExoPlayer handles supported HTTPS audio/video streams. The player is released when the playback screen is left. The app does not turn metadata records into playable streams unless a provider returns an explicit media file URL and the source provides a license URL.

## Settings and privacy

- TMDB access tokens are stored encrypted using AES/GCM; the AES key is generated and retained by Android Keystore.
- User feed configuration, favorites and recently opened catalog cards are stored in app-private SharedPreferences.
- API tokens and source links must not be printed to logs.
- RSS indexer URLs require HTTPS and reject URLs containing user-info credentials.

## Native torrent lifecycle

The torrent engine owns a single SessionManager. JNI handles do not escape the engine; the UI observes immutable snapshots. The local queue stores magnet links and pause state. Full libtorrent resume-data persistence, .torrent import into the internal engine, file priorities and download scheduling are still planned.

## Android 15+

The service currently uses foreground-service type `dataSync`. Android 15+ applies a cumulative background time limit to this type. A user-initiated data-transfer lifecycle and robust native resume-data restoration are needed before claiming uninterrupted long-running transfers.
