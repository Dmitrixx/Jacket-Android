# Changelog

## 2.8.0

- Apply atomic StateFlow mutations to embedded Lampac status and bounded logs so concurrent process output and service-status updates cannot overwrite one another; add a concurrent regression test.
- Close a PRoot session if service cancellation arrives while its process is being created, preventing an orphan Lampac process during a rapid stop/start.
- Reassemble complete Lampac PTY output lines before credential sanitization so split reads cannot bypass token/header redaction; cap oversized lines.
- Restrict embedded runtime artifact downloads and every redirect to pinned HTTPS hosts on port 443; reject user-info, fragments, and nonstandard ports with URL-policy regression tests.
- Validate the extracted .NET runtime before replacing an existing copy, and restore the previous runtime after a failed activation or an interrupted swap.

- Bound actual bytes written while extracting each Lampac ZIP entry; abort on entry expansion mismatch or truncated payload, and check coroutine cancellation between chunks instead of trusting central-directory sizes alone.

- Redact tokens, passwords, cookies, API keys, account identifiers and bearer credentials from embedded Lampac status text, runtime logs and foreground notifications.
- Consolidated native Filmix and HDRezka search/player adapters, encrypted account state, provider diagnostics and the unified detail-card source picker.
- Filmix AUTO mode checks the explicitly saved custom HTTPS API first (when present), then the official primary and reserve hosts; activation polling and catalog requests stay pinned to the host that issued the device code.
- Allow an explicitly user-entered HTTPS Filmix API host with strict URL/host validation, normal certificate validation and no cross-host token forwarding.

- Added the native optional Jackett indexer catalogue, dynamic per-indexer configuration/testing, private-indexer support and richer torrent release cards. Jackett remains user-managed and is not bundled.
- Added Jackett WebUI password login; the administrator password is transient, while only the returned session Cookie is stored in encrypted local settings.
- Extended release-title labels with CAMRip/TeleSync/TeleCine/SCR, 10-bit/Hi10P, HLG, HDR10+ and recognized dubbing studios; added an optional filter to hide screen-copy releases.
- Added a ten-provider catalogue in priority order. Only Filmix and HDRezka have direct adapters; ColdFilm, Baskino, Kinogo, VideoCDN, Collaps, Kinobase, CDNMovies and Lordfilm remain unconnected descriptors pending a compatible verified integration.
- Added completion-order partial tracker results, bounded detail-page resolution, a 25-second aggregate deadline and protection against stale search callbacks.
- Run torrent indexers, built-in HTML trackers, Filmix, HDRezka and Lampac detail-card searches concurrently; publish each provider's results as soon as they arrive, with a shared 60-second deadline and late-callback protection.
- Match the Android 12+ display refresh rate to the active video frame rate on SurfaceView playback; clear the request when playback pauses or stops and when the surface is detached. Older Android versions remain unchanged.
- Replace torrent piece polling with a condition signal driven by libtorrent piece-finished alerts; pause/removal, session shutdown and superseded seek windows wake waiting stream workers, cancelling stale HTTP Range work. Clear active stream leases before shutdown notification and check the stop state immediately before condition waits.
- Add a tail-piece deadline to the initial torrent byte-range plan, after the bounded forward window, and lower the global peer-connection ceiling on low-RAM Android devices.
- Add the Media3 1.11.1-matched LGPL FFmpeg audio extension for AC-3, E-AC-3, DTS, MLP and TrueHD fallback. Prefer extension renderers, preserve bundled license metadata, and expose the complete notices, LGPL text and exact source archive in the app.
- Shrink release resources and produce ABI-specific APKs for arm64-v8a, armeabi-v7a, x86 and x86_64 while retaining a universal APK.

- Restricted built-in HTML tracker requests, redirects and parsed download URLs to public HTTPS targets, including non-public IPv6 rejection. DNS rebinding is not fully prevented.
- Made initial torrent byte-range failures retryable with a sanitized HTTP 503 and Retry-After header before a successful response is committed.
- Hardened torrent persistence with bounded validation, per-record queue recovery, legacy migration, serialized resume checkpoints and AtomicFile backup recovery.
- Preserved saveable state across main, details, settings, downloads, player and individual navigation tabs.
- Clear stale saved resume offsets when playback is explicitly reset to 0 ms; keep the 90% watched cleanup threshold overflow-safe.

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
- Added an ordered forward byte window so downloads can prioritize the pieces needed for smooth startup and seeking.
- Added streaming-safe headers, MIME detection, and dynamic torrent stream URLs.
