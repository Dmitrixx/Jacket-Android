# Changelog

## 2.5.0

- Added an optional Jackett connector for a user-managed LAN or HTTPS server.
- Added `t=caps` connectivity checks, indexer filter selection and tracker attribution in source results.
- Improved Torznab parsing for Jackett tracker tags, comments, sizes, total peers and API error responses.
- Added a strict local-network HTTP policy; public indexers continue to require HTTPS.
- Enabled authenticated .torrent downloads from a local Jackett endpoint with origin-restricted credentials and redirect handling.
- Increased ExoPlayer's read timeout for loopback torrent streams so slow swarms have time to provide requested pieces.
- Preserved the existing in-process libtorrent engine, tokenized loopback stream server, Filmix pairing, HDRezka settings and built-in tracker shortcuts.

## Compatibility notes

- Jackett is not bundled and is not required. Direct RSS/Atom and Torznab sources continue to work without it.
- The uploaded Jacket 2.1.0 source set was not copied over the current application wholesale. Older duplicate torrent server, player, activity, manifest and Gradle files were superseded by newer implementations in the current main branch. Compatible Jackett functionality was merged into the current architecture instead.
