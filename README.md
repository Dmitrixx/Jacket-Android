# Jacket — Android media center

Jacket is evolving from a Jackett companion into a self-contained Android media center: poster-based film and series catalog, direct indexer federation, favorites, watch history, in-app playback for licensed media, and an integrated BitTorrent queue.

**A separate Jackett server is not required.** The app does not call the Jackett API. It queries its own providers directly on the device.

> The project is under active development. A green GitHub Actions build and real-device playback/network testing are required before treating a commit as a release.

## Main areas

- **Home** — featured title and horizontal shelves of recommendations and collections.
- **Movies / Series** — poster cards, ratings, years, descriptions and details.
- **Search** — search the built-in catalog and metadata provider.
- **My library** — favorites and recently opened cards.
- **Downloads** — integrated libtorrent4j queue, progress, speed, peers, pause and resume.
- **Settings and sources** — optional TMDB token, configurable RSS/Atom and Torznab/Newznab aggregators, credentials, category filters, freeleech filter, sort order, connection tests and manual search.
- **Android TV** — Leanback launcher entry, TV home-screen banner, D-pad navigation rail and larger focused poster cards; the same APK remains usable on phones and tablets.

## Built-in providers — no Jackett server

### Internet Archive catalog

The app queries the Internet Archive Advanced Search endpoint and reads item metadata directly. It can show the catalog, poster thumbnails, descriptions, dates and creator information. On a detail page it looks for video and torrent files.

Jacket only offers in-app playback / direct archive torrent actions when the item metadata includes a license URL. The presence of a file alone is not treated as proof of permission. Always review the source item's license and access conditions.

### TMDB metadata (optional)

To display current popular films, series, ratings, posters and expanded descriptions, enter your own TMDB API Read Access Token in Settings. The token is encrypted with Android Keystore. TMDB provides catalog metadata and artwork; it does not provide the film/video stream.

This product uses the TMDB API but is not endorsed or certified by TMDB.

### User-configured indexers

Add your own HTTPS RSS/Atom feeds or Torznab/Newznab-compatible endpoints in Settings. Each search URL must include `{query}`, which Jacket replaces with the URL-encoded search text. Torznab requests can include API-key authentication, categories, tags, freeleech filtering, sort field and order.

Private indexers can use HTTP Basic username/password, an API key, or a manually supplied session cookie. Credentials are encrypted at rest with Android Keystore (AES/GCM). Indexers can be enabled/disabled, edited, individually tested or tested together; the settings page also provides manual cross-indexer search.

Example Torznab URL:

```text
https://your-authorized-indexer.example/api?t=search&q={query}
```

The protocol adapter handles standard Torznab/Newznab XML attributes and RSS/Atom entries. A site requiring a proprietary login flow, CAPTCHA, anti-bot challenge or interactive 2FA is not automatically supported; a user-provided session cookie may work where the source permits it.

Example format:

```text
https://your-authorized-source.example/search/rss?q={query}
```

Jacket fetches feeds directly from the phone, parses RSS/Atom entries and common torrent attributes, and merges results. There is no Jackett URL or Jackett API key. Sources are user-managed; only add sources you are authorized to access and use.

The built-in adapter layer is intentionally extensible: new provider adapters can be added without changing the catalog UI.

## Playback and downloads

- Media3 / ExoPlayer provides in-app playback for supported HTTPS video streams.
- Existing libtorrent4j engine handles magnet links.
- Direct `.torrent` URLs are currently handed to an installed torrent-capable app; importing them into the internal engine is a planned enhancement.
- The download queue is stored locally. Full native libtorrent resume-data persistence is not yet implemented.
- Download files are stored in the app-private `Android/data/app.jacket.mobile/files/Downloads` directory.

## Requirements

- Android 9 (API 28) or newer.
- Android TV / Google TV is supported by the same APK; use the remote's D-pad, OK and Back buttons.
- Build: JDK 17, Android SDK Platform 36, Build Tools 36.0.0, Gradle 9.5.0.
- A TMDB API Read Access Token is optional; Internet Archive browsing works without it.
- Internet access is required for online catalogs, RSS feeds, poster images and remote media streams.

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK:

`app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions runs unit tests and builds a debug APK on pushes and pull requests to `main`. Release tags use the `v*` pattern.

## Security and content rights

- TMDB token, indexer API keys, usernames/passwords and session cookies are encrypted with AES/GCM using a key held by Android Keystore.
- TV builds declare Leanback support as optional and touchscreen as not required, with a dedicated TV launcher banner.
- Catalog and RSS requests use bounded response sizes and timeouts.
- Custom indexers require HTTPS and cannot embed URL user-info credentials.
- The app does not ship lists of piracy trackers, bypass paywalls, or claim that an arbitrary source is licensed.
- Use only media and torrent sources you have the right to access and use. See [SECURITY.md](SECURITY.md).

## Android background limits

The integrated torrent service currently uses Android foreground-service type `dataSync`. Android 15+ applies a cumulative background time limit to this service type. It is not yet a guarantee of uninterrupted all-day torrent seeding. A separate user-initiated transfer lifecycle and native resume-data persistence remain on the roadmap.

## Architecture

See [docs-ARCHITECTURE.md](docs-ARCHITECTURE.md), [CHANGELOG.md](CHANGELOG.md) and [CONTRIBUTING.md](CONTRIBUTING.md).
