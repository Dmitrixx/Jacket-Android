# Embedded Lampac runtime inside Jacket

**Status:** embedded runtime code is present on the personal-use development branch. Android CI and physical-device validation are still required; this is not yet a confirmed working install.

## Goal

Run Lampac NextGen on the same Android device as Jacket. No Termux, root access, separate Lampac APK, PC or NAS is required. The existing LampacBridgeClient remains the only bridge used by the catalogue and player.

The embedded server must listen on **127.0.0.1:9118 only**. Remote Lampac remains available for devices where the embedded ARM64 engine is unsupported.

## What is now implemented in the draft branch

- Pin oonid/pr at commit 3ba86577f1f393a5e4ff6a92b3d4de08bb35786f and include its standalone Android :proot-engine module. Only the engine Kotlin sources, JNI PTY and ARM64 native binaries are included; the upstream terminal UI, :session-core and :termlib are not.
- Keep PRoot executables and its loader in Android's extracted native-library directory, then prepare the app-private PRoot prefix, home and cache.
- Install an ARM64 Debian OCI rootfs via pr-cli install debian; accept it only when `/etc/os-release` identifies Debian and `/bin/sh` plus `/usr/bin/apt-get` are executable.
- Install ASP.NET Core Runtime 10.0.15 and Lampac NextGen 1.90.2 from pinned HTTPS URLs with SHA-512 / SHA-256 verification.
- Extract and stage the Lampac payload under Debian /opt/lampac, validate required managed files, create an Android-specific init.conf, then atomically switch the installation directory.
- Generate a conservative first-run config: bind to 127.0.0.1:9118, turn Chromium and openstat off, and skip optional browser/transcoding and unrelated server modules.
- Add a foreground service with install, start, stop, health-check and bounded log state. The startup path also retains and closes a just-created PTY session if cancellation races with process creation. It starts only after an explicit user action; there is no boot receiver or automatic startup.
- Add Settings controls for install/start, stop, status, recent output, and connecting http://127.0.0.1:9118 to Jacket's existing Lampac bridge.
- Keep the remote Lampac address and account settings as a fallback.
- Disable public APK uploads and public GitHub Release publishing in this personal-use workflow.

## Runtime diagnostics and credential handling

Lampac provider diagnostics can contain request URLs, account identifiers, cookies or authorization headers. PTY output is reassembled into bounded complete lines before redaction, so credentials split across native read buffers are still sanitized. Jacket redacts common token, password, API-key, cookie, bearer and account-identifier fields before writing runtime logs, status text or foreground notifications. Keep this sanitization in the shared logging path; do not add a raw-output UI or diagnostic export. The redactor is a best-effort safety boundary, not a reason to write credentials to upstream logs deliberately. Runtime status and bounded-log changes use atomic StateFlow updates, preventing concurrent process output and service status changes from dropping each other's snapshot fields.

## Runtime versions and storage

The pinned Lampac 1.90.2 archive is 480,375,787 bytes before extraction. The installer also downloads the ASP.NET Core 10.0.15 ARM64 runtime. The preflight requires at least 1.5 GB free before downloading; after the archive is downloaded, the ZIP installer calculates the uncompressed size and checks that additional free space is available before extracting. A real device may need more than the preflight minimum, depending on the release archive and Debian package state.

Both payloads are verified before use. Downloads are staged as temporary files and moved into place only after the checksum matches. The .NET runtime is unpacked to a staging directory and checked with `dotnet --info` before the existing runtime is replaced; failed activation restores the previous directory. Every redirect is revalidated against the pinned HTTPS host allowlist and standard port 443; user-info, fragments, and non-HTTPS URLs are rejected. ZIP extraction rejects traversal paths, bounds file count and declared expansion size, verifies the actual uncompressed byte count of every entry against its central-directory size and per-entry limit, observes cancellation between copy chunks, and stages into a temporary directory before replacing the current Lampac directory.

The upstream Lampac system installer is deliberately not run: it writes system users, host paths and systemd units that do not belong inside Jacket's app-private PRoot rootfs.

## Android and ABI constraints

The embedded PRoot module is currently **ARM64-only**. Other ABIs can continue using a remote Lampac server.

Jacket compiles against API 36 but targets API 35 in this branch to match the PRoot project's documented and tested targetSdk 35 execution path. PRoot relies on its loader in nativeLibraryDir to work around Android's app-data executable restrictions. Moving the app target to API 36 is not assumed safe until the PRoot engine has been validated there.

The Android 12 handset still requires a physical test. CI can verify source compilation and packaging, but cannot validate Android's SELinux, PRoot syscall behaviour, DNS, actual process lifetime, memory pressure or media playback on the phone.

## Disabled capabilities in the first profile

Chromium/browser automation, Playwright-dependent providers, transcoding, TorrServer, catalog-related extras, DLNA, sync, time-code, tracks and web logs are not part of the initial embedded profile. Provider availability must reflect these disabled features; Jacket must not claim full upstream Lampac parity.

The initial config intentionally prioritizes the no-browser API path used by Jacket. Modules will be enabled only after provider/API and playback tests.

## Upstream TLS caveat

The pinned Lampac source configures its internal `proxy`, `proxyRedirect` and `proxyimg` HTTP clients with `RemoteCertificateValidationCallback` that accepts all certificate results. Those upstream proxy paths therefore do not enforce server-certificate validation. This is inherited upstream behavior, not a Jacket setting, and this integration does not patch it.

The embedded listener is restricted to `127.0.0.1:9118` and must not be exposed to Wi-Fi/LAN or the public internet. Use this only on a trusted device/network, avoid entering sensitive third-party credentials into Lampac modules, and treat returned media URLs as untrusted.

## Licence and personal use

Lampac NextGen is AGPL-3.0-only. The PRoot Android wrapper is MIT, while its PRoot and pr-cli native components have GPL-2.0-or-later / GPL-3.0-or-later licensing. Source provenance and notices are retained. This branch is for a personal build; it does not publish APKs or create public releases. Any future redistribution would require a full corresponding-source and licence-compliance review.

## Validation still required

1. A green Android CI run for the current branch: lint, unit tests, debug build and release build.
2. On the actual ARM64 Android 12 phone, start Debian from Jacket's private files directory without root or Termux.
3. Run dotnet --info inside Debian and verify the ASP.NET Core Runtime 10.0.15 installation.
4. Launch Core.dll and verify /online.js, /lite/events and /lifeevents.
5. Verify the server is reachable from Jacket through loopback but is not reachable from another device on the Wi-Fi network.
6. Test at least one supported non-browser provider and a real playback route.
7. Test stop/restart, interrupted installation, low storage, app process death, Android background restrictions, RAM, disk use, heat and battery drain.

**Do not mark embedded Lampac complete until CI and the on-device checks pass.**
