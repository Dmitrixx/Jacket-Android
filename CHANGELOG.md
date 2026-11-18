# Changelog

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
- Encrypted the optional TMDB Read Access Token with Android Keystore AES/GCM.
- Removed the requirement for a running Jackett server and its Torznab API key.

## 1.2.0

- Added initial libtorrent4j engine, DHT, foreground service and torrent task queue.
- Added Torznab integration through a Jackett server (superseded by the in-app indexer registry in 2.0.0).

## 1.1.0

- Prepared native BitTorrent engine and service lifecycle.

## 1.0.0

- Initial Jackett-connected search prototype.
