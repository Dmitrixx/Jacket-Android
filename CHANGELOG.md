# Changelog

## 3.0.39 — Fix ARMv7 Debian architecture selection

- Pin 32-bit Android to the ARMv7-only Debian 13 image instead of the generic multi-architecture Trixie tag.
- Verify that Debian's installed dpkg architecture is `armhf` before reusing an ARM32 rootfs; a mismatched `armel` rootfs is rebuilt.
- Preserve and migrate the existing Lampac `init.conf` during the rootfs repair.
- Add regression tests for the ARMv7 image selection and Debian ABI validation.

## 3.0.38 — Avoid inherited setgid permissions in Debian package staging

- Build the local PRoot init-provider DEB under the Debian rootfs `/root` instead of Android-bound `/tmp`, which can inherit setgid mode 2755.
- Explicitly clear the setgid bit from the generated control directory and validate its final mode is exactly 755 before invoking `dpkg-deb`.
- Extend installer tests to assert rootfs-local staging, exact mode validation and absence of the old `/tmp` path.


## 3.0.37 — Fix Debian PRoot init-provider package permissions

- Set the generated package control directory to mode 0755 and its control file to 0644 before invoking `dpkg-deb`.
- This handles Android/PRoot umask 077, which otherwise creates the DEBIAN control directory as 0700 and makes dpkg-deb reject the local init-provider package.
- Add unit assertions for the generated metadata permissions.


## 3.0.36 — Repair Debian PRoot init dependency resolution

- Stop installing `sysvinit-core` as an APT workaround: Debian Trixie's `sysvinit-core` conflicts with `systemd-sysv` and can leave the solver with an impossible dependency set.
- Register a tiny app-owned `Provides: init` package inside the private PRoot rootfs; Android owns PID 1, so the Debian container does not need an init daemon.
- Purge leftover `systemd-sysv` and `sysvinit-core` package states before package recovery, while keeping systemd packages excluded from APT.
- Add unit assertions covering the local init provider and ensuring runtime package installation no longer requests `sysvinit-core`.


## 3.0.35 — Restore Lampac installer build

- Restore the shared .NET runtime installation command used by both ARM64 and ARMv7 installers.
- Keep staged runtime validation and automatic rollback to the previous .NET directory when activation fails.
- Retain tests for archive-path validation, safe quoting and recovery of the previous runtime.
- Keep the Debian PRoot package-selection and systemd-exclusion fixes from 3.0.34.


## 3.0.22 — Support 32-bit ARM Android TV devices

- Add an ARMv7 PRoot and `pr-cli` build so embedded Lampac can run on 32-bit Android userspace.
- Select Debian, .NET 10, Node.js and Chromium packages matching the Android process ABI; keep the existing ARM64 Google Chrome and runtime path.
- Keep the full Lampac payload, configuration migration, media proxy and Lampa player integration shared between both architectures.
- Add CI generation and ELF validation for the ARMv7 native runtime plus unit coverage for ABI selection.
- Physical-device validation on 32-bit Android TV hardware is still required.


## 3.0.20 — Lampac controls inside Lampa settings

- Remove the separate floating Lampac button from the WebView screen.
- Add a remote-navigable Lampac section to Lampa's own Settings menu, next to the server settings.
- Open the existing native Lampac control panel from that section; keep install/start/stop, module management and both update flows intact.
- Expose the menu entry only when the installed APK provides the matching native bridge, preserving compatibility with older APKs receiving standalone Lampa web updates.

## 3.0.19 — In-app Lampa and Lampac updates

- Add an in-app update center with separate checks and actions for the Lampa web bundle and the embedded Lampac server.
- Publish checksum-manifested standalone Lampa web bundles from main builds so the Lampa interface can update without reinstalling the APK.
- Check the latest stable Lampac release and install only a newer semantic version with a published SHA-256 and bounded archive size.
- Stage, validate and swap updates atomically; preserve `init.conf`, local data, databases, user modules and configured overrides.
- Keep the previous Lampac installation until the new server passes its startup probe; automatically restore the previous version if the updated server fails to start.
- Keep the previous Lampa web bundle for recovery; re-create the WebView after a successful asset update.
- Add a weekly upstream Lampa source check that prepares a reviewable pull request; Android compatibility patches are checked by CI before an upstream sync can be merged.

## 3.0.18 — Balancer selection and external-player diagnostics

- Keep every checked Lampac balancer in the source selector, including grey entries marked as no-result, so the user can force-open/retry each provider.
- Preserve automatic selection of the best positive match; this change does not merge unrelated provider results into one combined list.
- Patch `Online/plugin.js` in-place before server startup; no Lampac reinstall or settings reset is needed.
- Add external-player relay diagnostics to Lampac logs: HTTP status and media content type for HEAD probes, playlists, and failed responses, without logging encrypted stream tokens or query strings.
- Add unit tests for idempotent selector patching and retain the existing Aladin short-proxy fix.

## 3.0.17 — Fix long Aladin proxy links

- Patch the dynamic Aladin controller before Lampac starts, enabling compact MD5 proxy keys for HLS URLs with large `Accepts-Controls` headers.
- Keep the generated `/proxy/` URL below Kestrel's request-line limit by storing headers server-side instead of serializing them into the URL.
- Apply the patch in-place to existing Lampac installs and make it idempotent; preserve `init.conf` and provider credentials.
- Add tests for the source patch, runtime file discovery and repeated startup.

## 3.0.16 — Fix external playback of Lampac online sources

- Route Lampac `/proxy/` and `/proxy-dash/` media URLs through the loopback compatibility server, alongside TorrServer/GStreamer streams.
- Route VLC/MX Player HEAD probes through the compatibility relay because Lampac's BaseMod middleware rejects HEAD before the `/proxy/` media handler.
- Forward the compatibility listener as the upstream Host so generated playlist and segment URLs continue to use the reachable proxy port.
- Keep HEAD-to-GET handling, Range support and raw HTTP response framing for online VOD and DASH streams.

## 3.0.15 — Repair external torrent stream relay

- Relay Lampac's raw HTTP response framing instead of using HttpURLConnection, which removes chunked transfer framing.
- Preserve Content-Length, Transfer-Encoding, Content-Range, redirects and streamed body bytes for VLC/MX Player.
- Forward the original Range and media negotiation headers, while keeping Authorization and Cookie headers out of the local proxy.
- Start the loopback player proxy on demand before handing media to another app.
- Set both Intent.data and EXTRA_STREAM for better compatibility with Android video players.

## 3.0.14 — Fix external playback of Lampac torrents

- Add a loopback-only media proxy for external Android players.
- Convert the player's HEAD media probe into a GET-to-headers probe because Lampac 1.90.2 blocks HEAD before the TorrServer route.
- Forward byte-range requests and stream responses without buffering the full video.
- Keep the compatibility proxy active only while the embedded Lampac foreground service is running.
- Add an in-app module manager that discovers available modules from their installed manifest.json files.
- Save per-module exclusions to BaseModule.SkipModules, preserve unrelated provider settings and custom exclusions, and restart Lampac to apply changes.


## 3.0.13 — Route torrent playback to external Android video players

- Intercept Lampa's earlier torrent-specific inner-player branch before the generic HTML5 fallback.
- Convert torrent source URLs and playlist entries through `Torserver.toPlayUrl` before handing them to the Android external-player chooser.
- Preserve the stream metadata and playlist for compatible video players.
- Fall back to Lampa's built-in player when the external chooser has no compatible handler or rejects the URL.
- Keep the change restricted to the embedded `Lampa-Android` WebView; browser and Media Station X playback are unchanged.

## 3.0.12 — Open Lampa streams in an external Android player

- Send Lampa media streams to Android's system video-app chooser by default inside the installed app.
- Pass the title, available request headers, playback position, playlist and subtitle URLs to compatible external players.
- Keep Lampa's HTML5 player as a fallback when the URL is unsupported or no installed video application can handle it.
- Keep external-player handoff restricted to the app's private `Lampa-Android` WebView marker; browser playback is unchanged.

## 3.0.11 — Avoid the false MediaStationX hint in the embedded app

- Keep the upstream Android APK recommendation in Chrome and Media Station X.
- Exempt only the app's private `Lampa-Android` WebView marker from that recommendation; the app is already an installed Android APK and uses its own native bridge.
- Preserve Lampa's web platform/player behavior instead of impersonating the full upstream Android client or silently switching torrent playback to an external Android player.

## 3.0.10 — Reduce persistent PRoot storage

- Clear verified OCI image-layer download cache after the Debian rootfs is confirmed installed; keep the Debian rootfs itself and only delete direct regular files in the dedicated cache directory.
- Remove unused development-header packages (OpenSSL, JPEG, PNG and WebP) from the runtime dependency set; runtime libraries remain installed through package dependencies.
- Report the reclaimed OCI cache size in the installation log.

## 3.0.9 — Replace the disabled sysusers stub with opensysusers

- The previous build returned success from `systemd-sysusers` without creating service accounts. Debian's `dbus-system-bus-common` requires that step to create the `messagebus` account, causing dbus and dependent GStreamer libraries to remain unconfigured.
- Install Forky's `opensysusers` compatibility provider before configuring pending packages.
- Forward the diverted `/usr/bin/systemd-sysusers` entrypoint to the opensysusers executable so package `.conf` files create their required users/groups without the systemd 260 kernel-dependent implementation.
- Continue disabling only systemd service-manager helpers and service starts, not sysusers account creation.
- Keep Debian rootfs, Lampac payload and provider configuration for in-place retries.


## 3.0.8 — Full Debian install log access
- Keep the final 160 installer log entries so the complete apt/dpkg tail is retained.
- Make the log panel vertically scrollable instead of showing only the last eight rows.
- Add a one-tap copy action for the full sanitized installer log so support can identify the failing Debian package.
- Keep the copy output sanitized by the existing Lampac log sanitizer.


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