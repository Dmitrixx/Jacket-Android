# Changelog

## 3.0.7 — POSIX-safe Debian installer diagnostics

- Capture dpkg and apt output with POSIX shell redirection instead of Bash-only pipeline status variables.
- Display the final 120 log lines and preserve the original dpkg/apt exit code.
- Keep the temporary log until diagnostics are emitted, then remove it.
- Keep existing Debian rootfs and Lampac configuration intact.


## 3.0.6 — Debian package failure diagnostics
- Capture apt/dpkg output during system-library installation and Node.js repair.
- On failure, show the final 120 lines of the package log, including the failing package's maintainer-script output.
- Remove the temporary log after success.
- Preserve Debian rootfs and Lampac configuration during retries.


## 3.0.5 — PRoot systemd-sysusers kernel compatibility

- Divert `/usr/bin/systemd-sysusers` to an app-managed success stub before dpkg recovery and package installation.
- Address the systemd 260 `STATX_MNT_ID` dependency, which is unavailable on the POCO F3's Android 4.19 kernel and is surfaced as a misleading missing `basic.conf` error.
- Keep systemd-managed service account provisioning disabled in the app-private rootfs; the Android app starts Lampac directly and does not run systemd daemons.
- Preserve the Debian rootfs, Lampac files and provider configuration during retry.

## 3.0.4 — PRoot systemd divert

- Replace `/usr/bin/systemctl` and `/usr/bin/systemd-machine-id-setup` with permanent stubs before `dpkg --configure`. Debian maintainer scripts call those absolute paths, so the previous PATH shim never ran, and PRoot's `statx` failure (`Protocol driver not attached`, apt exit 100) aborted installation of the full Lampac runtime.
- Keep the stubs across later package upgrades with `dpkg-divert`. Do not restore the real systemd helpers: Lampac is started by Android, not by systemd.
- Ignore service starts through `/usr/sbin/policy-rc.d` (exit 101) and clear stale dpkg locks left by the interrupted attempt. An in-place update can resume the existing Debian rootfs; deleting app data is not required.

## 3.0.3 — PRoot systemd machine-id shim

- Use a temporary `/usr/local/bin/systemd-machine-id-setup` shim for the complete dpkg/apt transaction, including package upgrades and post-install scripts.
- Keep a valid persistent machine-id in the app-private Debian rootfs.
- Restore any pre-existing helper after the transaction, including on command failure.
- Avoid editing Debian's `systemd.postinst` file, which may be replaced when apt unpacks or upgrades the systemd package.

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