# Changelog

## 3.0.2 — PRoot systemd post-install repair

- Provision a persistent machine-id inside the private Debian rootfs before package configuration.
- Temporarily intercept the systemd machine-id setup helper during dpkg transactions because systemd is not PID 1 in Android PRoot.
- Keep the workaround scoped to package transactions and remove it automatically after success or failure.
- Recover interrupted dpkg configuration without replacing the Debian container or Lampac data.

## 3.0.1 — PRoot package installation repair

- Run Debian package maintainer scripts against the offline PRoot root instead of Android's init environment.
- Recover any interrupted dpkg configuration before installing Lampac's native dependencies or repairing Node.js.
- Keep the existing Debian container, Lampac payload and provider configuration during an in-place APK update.

## 3.0.0 — Lampa-only refactor

- Retained the complete upstream Lampa client and its own HTML5 player.
- Replaced Jacket catalog/search/player UI with a minimal Lampa Android host.
- Pinned the complete Lampac NextGen source and kept the full checksum-verified upstream runtime archive.
- Removed app-enforced module exclusions and configured the server to load the complete module set.
- Added Chromium, FFmpeg/FFprobe, GStreamer and the native dependencies used by the upstream deployment.
- Preserved the application ID, signing configuration and app-private Lampac data for in-place updates.