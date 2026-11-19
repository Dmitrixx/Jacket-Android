# Changelog

## 2.6.0

- Fixed Filmix device pairing API hostname to match the HTTPS certificate and added readable TLS diagnostics without weakening certificate checks.
- Expanded HDRezka mirror validation to support changing public HTTPS mirror domains; added a mirror reachability check and clearer challenge/login failures.
- Added in-app HTML parsing adapters for RuTracker, NNM-Club, Rutor, Kinozal, 1337x and The Pirate Bay.
- Added per-tracker Cookie storage encrypted with Android Keystore; cookies are sent only to the tracker origin and never exported.
- Tracker selections now produce an in-app release list rather than opening the tracker search page in a browser.
- Tapping a magnet or .torrent release starts the in-process torrent task, selects the largest playable video file (excluding samples/pad files), waits for metadata and opens the ExoPlayer stream automatically.
- Added integration-facing parser tests for tracker results, detail/download links, relative URLs and mirror validation.

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
- Tracker adapters parse the public HTML that each site returns. Markup changes, login requirements, CAPTCHA, Cloudflare challenges, IP/network blocks and unavailable mirrors can still prevent a particular tracker from responding. The app does not bypass those controls.
- If a tracker publishes a .torrent download rather than a magnet, Jacket downloads that metainfo internally, imports it into libtorrent and starts playback without handing off to a browser.
