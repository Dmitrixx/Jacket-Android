# Changelog

## Next

- Federate the detail-card source picker across Filmix API v2, the configured HDRezka mirror, custom torrent indexers, built-in tracker adapters and license-gated Internet Archive media. Direct provider cards show source type, selected translation and server-provided quality; premium-only options link to their provider page.
- Pass only allowlisted non-secret Referer/User-Agent headers and parsed subtitles to Media3. Never forward account cookies to media CDNs.

- Filmix device pairing now lets the user explicitly choose the default API or the alternate HTTPS API published by Filmix support. The selected host is probed before a device-specific request; there is no automatic host fallback and TLS verification remains enabled. TLS hostname, expiry and trust-chain failures receive distinct diagnostics.
- HDRezka no longer labels any non-empty Cookie string as an authenticated session. A saved session must contain both DLE auth cookie fields; the UI clarifies that this is a format check, not a live server validation. The catalogue/stream adapter remains unimplemented.

- Recover fast-resume checkpoints through `AtomicFile.openRead()`, which restores the last committed backup after an interrupted write. Serialize checkpoint reads, writes and deletes, reject oversized snapshots, and remove stale AtomicFile backups when a task is deleted or its checkpoint is invalid.

- Persist torrent queue and pause-state mutations synchronously before acknowledging service commands, reducing the window where a force-stop can lose a newly added or paused task.

- Preserve Jackett's masked previous-password marker when a private tracker password field is left unchanged or edited and cleared; add regression tests for both preserve and replace paths.
- Add a native per-tracker Test action using Jackett's REST endpoint, refresh the server-reported last-error state, and redact API keys, cookies and configured credentials from diagnostics.
- Reject dot-segment indexer identifiers before building Jackett REST paths.

- Add Jackett's native REST indexer catalogue (`GET /api/v2.0/indexers`) and dynamic per-indexer setup forms through its authenticated config API; the API key remains for Torznab search while an optional encrypted dashboard Cookie authorizes setup.
- Keep torrent release results inside Jacket and enqueue direct `.torrent` links from the indexer cabinet instead of opening the download URL in a browser. Private tracker setup forms now support site URL, username/password, freeleech, sorting, order, categories, tags and dynamic Jackett field types.
- Expand release cards with feed publication dates, resolution dimensions, audio channels, language/subtitle markers and title-derived bitrate tags.
- Add a ten-provider source registry in the requested priority order, while marking only HDRezka and Filmix as integrated and the remaining providers as unconnected pending a verified adapter.
- Bound the HDRezka login response inspection to a small preview, remove the default restricted-domain suggestion, and stop relying on an undocumented Filmix HTTPS fallback.

- Avoid buffering the full HDRezka landing page during mirror discovery and login; only a bounded preview is read when checking for browser challenges.
- Align Filmix device authorization with the known-compatible Android API fingerprint and report per-host HTTPS failures without exposing request parameters.
- Make torrent search explicit on the movie detail card, automatically search enabled in-app trackers, and offer an opt-in way to add tracker shortcuts when none are configured.


## 2.6.0

- Added a Lampa-style source picker sheet that opens automatically from movie and episode playback actions.
- Replaced global sequential torrent mode during playback with HTTP Range-driven piece deadlines, starting at the exact requested byte position.
- Added an ordered forward prefetch window capped at 8 MiB / 32 pieces; seek requests move the priority window instead of restarting from byte zero.
- Added a 32 MiB LRU disk ring for recently served byte ranges, including overlapping-range reuse and automatic eviction.
- Kept playback cache buffers bounded to 256 KiB per active stream worker; cache eviction never deletes verified torrent payload data.
- Added JVM tests for range-to-piece mapping, prefetch bounds, LRU eviction, overlapping byte-range hits and loopback Range delivery.

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
