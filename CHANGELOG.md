# Changelog

## 3.0.0 — Lampa-only refactor

- Retained the complete upstream Lampa client and its own HTML5 player.
- Replaced the previous app-specific catalog, search and playback implementation with a minimal Lampa Android host.
- Pinned the complete Lampac NextGen source and kept the full checksum-verified upstream runtime archive.
- Removed app-enforced module exclusions and configured the server to load the complete module set.
- Added Chromium, FFmpeg/FFprobe, GStreamer and the native dependencies used by the upstream deployment.
- Preserved the application ID, signing configuration and app-private Lampac data for in-place updates.