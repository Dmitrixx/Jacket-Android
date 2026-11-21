# Changelog

## 2.8.0

- Consolidated native Filmix and HDRezka search/player adapters, encrypted account state, provider diagnostics and the unified detail-card source picker.
- Added the native optional Jackett indexer catalogue, dynamic per-indexer configuration/testing, private-indexer support and richer torrent release cards. Jackett remains user-managed and is not bundled.
- Added a ten-provider catalogue in priority order. Only Filmix and HDRezka have direct adapters; ColdFilm, Baskino, Kinogo, VideoCDN, Collaps, Kinobase, CDNMovies and Lordfilm remain unconnected descriptors pending a compatible verified integration.
- Added completion-order partial tracker results, bounded detail-page resolution, a 25-second aggregate deadline and protection against stale search callbacks.
- Restricted built-in HTML tracker requests, redirects and parsed download URLs to public HTTPS targets, including non-public IPv6 rejection. DNS rebinding is not fully prevented.
- Made initial torrent byte-range failures retryable with a sanitized HTTP 503 and Retry-After header before a successful response is committed.
- Hardened torrent persistence with bounded validation, per-record queue recovery, legacy migration, serialized resume checkpoints and AtomicFile backup recovery.
- Preserved saveable state across main, details, settings, downloads, player and individual navigation tabs.
- Extended title-only release labels with CAMRip/TeleSync/TeleCine/SCR, 10-bit/Hi10P, HLG, HDR10+ and recognized Russian dubbing studios; added an opt-in filter to hide screen-copy releases.

## 2.7.0

- Fullscreen cinema player for phone and Android TV. Controls sit on top of the video and hide while playback continues.
- D-pad left/right seeks by 10 seconds when the overlay is hidden. Back hides the overlay first, then leaves the player. Center and media keys play and pause.
- Playback speed from 0.5× to 2×, picture fit / fill / zoom, and a sleep timer at 15, 30, 45 and 60 minutes. Speed and fit are remembered on the device.
- Phone picture-in-picture so a title can stay on screen after leaving Jacket. The player keeps the activity across rotation and PiP.
- Seek bar, elapsed time and remaining time in the overlay. Tap the left or right side of a hidden player to jump 10 seconds.
- Continue watching now shows the remaining time from the saved position.
- Includes the previously unreleased fixes already on main: bounded HDRezka page preview, Filmix device-authorization diagnostics, and explicit in-app torrent search on movie and episode cards.
- Picture-in-picture state is observed through the activity callback, so the build does not depend on an ActivityX type missing from this SDK line.

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
