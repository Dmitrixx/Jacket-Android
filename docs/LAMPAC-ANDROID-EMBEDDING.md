# Embedded Lampac runtime on Android — feasibility and implementation plan

**Status:** feasibility design / implementation gate. This document does not claim that Lampac is already embedded, that an Android runtime has been built, or that the design has passed a physical-device test.

## Product target

A user installs one Jacket APK. From **Settings → Lampac engine**, they can install or update the engine, start/stop it, inspect status and logs, and enable automatic start when a Lampac-backed search is requested. No separate Termux, Debian app, NAS, PC, Jackett, or TorrServer installation is required.

The existing LampacBridgeClient remains the app-facing API client. The embedded engine should expose the same local API contract so the source picker and Media3 playback pipeline do not need a second implementation.

## Current Lampac constraints

The upstream Lampac NextGen project is a server application, not an Android library:

- Core/Core.csproj uses Microsoft.NET.Sdk.Web and targets net10.0.
- Core/Program.cs creates an ASP.NET Core host and starts Kestrel. It also uses assembly loading, Roslyn compiler services and optional Playwright browser initialization.
- The official native Linux installer documents Debian/Ubuntu on amd64 and arm64 and installs the ASP.NET Core 10 runtime plus OS dependencies. The upstream Dockerfile additionally provisions browser, GStreamer and FFmpeg dependencies.
- The sample configuration sets listen.ip to 0.0.0.0. Jacket must not use that setting for an embedded instance: the embedded service must listen on **127.0.0.1 only**.
- The upstream project declares AGPL-3.0-only. Packaging its release, modifying it, or distributing a derived server requires a deliberate licence and corresponding-source compliance review before APK publication.

References:
- Upstream source: https://github.com/lampac-nextgen/lampac
- Upstream project configuration: https://github.com/lampac-nextgen/lampac/blob/main/config/example.init.conf
- Upstream Linux installer: https://github.com/lampac-nextgen/lampac/blob/main/install.sh
- Upstream container dependencies: https://github.com/lampac-nextgen/lampac/blob/main/Dockerfile
- .NET Android / Bionic NativeAOT limitations: https://github.com/dotnet/runtime/blob/main/src/coreclr/nativeaot/docs/android-bionic.md

### Why we must not simply copy Core.dll into the APK

The existing Lampac application is an ASP.NET Core server with a Linux/Windows runtime and native dependencies. Putting Core.dll in assets does not provide the ASP.NET runtime, compatible OS libraries, executable host, browser stack or process lifecycle.

The .NET Android workload is intended to build Android applications. The documented Bionic NativeAOT path is a limited native-executable/shared-library target; it is not a drop-in Android deployment target for an existing dynamic ASP.NET Core server. Lampac also loads assemblies and supports runtime C# compilation, which makes a direct NativeAOT conversion a high-risk route.

A direct Kotlin port of all Lampac providers would avoid a Linux userspace but would be a separate, large compatibility project. It should not be presented as a simple packaging change.

## Strong candidate: reuse an Android PRoot engine

Research identified **oonid/pr** (https://github.com/oonid/pr), an Android project specifically designed to run Linux distributions inside an APK without root and without a separate Termux installation. It is much closer to the requested user experience than asking users to install Termux.

Its source currently provides:
- An Android library module named proot-engine with Kotlin ProotHost / ProotLauncher APIs and ARM64 native binaries for PRoot, its loader and pr-cli.
- A session-core Android library with rootfs bootstrap, process execution, session supervision, foreground-service notifications and cancellation.
- Debian and other ARM64 userspace installation from OCI images.
- Android-specific handling for syscall / seccomp and executable-file restrictions.

Important limits before adopting it:
- The published project currently declares its PRoot engine ABI as arm64-v8a. Jacket supports additional ABIs, so embedded mode must be explicitly disabled where no matching engine exists or more ABIs must be built and tested.
- Its Android libraries currently use compileSdk 37 and its documented build uses a recent Android Gradle Plugin, NDK and Rust toolchain. Compatibility with Jacket's existing Gradle/SDK configuration must be verified rather than assumed.
- The project is young and must be audited and tested on Jacket's target devices. Its README documents PRoot's syscall interception and performance limits; this is not a VM or a full Linux kernel.
- Its licence is multi-component: the Android source is MIT, but the PRoot and pr-cli native components are GPL-2.0-or-later / GPL-3.0-or-later. The whole dependency must not be treated as MIT. Jacket's distribution licence and obligations for redistributing these binaries must be resolved before vendoring them.
- The PRoot process can run a normal long-lived command, but it does not provide systemd or real root privileges. Lampac must be launched directly as a supervised process, not as a Linux system service.

Reference files:
- https://github.com/oonid/pr
- https://github.com/oonid/pr/blob/main/LICENSE
- https://github.com/oonid/pr/blob/main/android/proot-engine
- https://github.com/oonid/pr/blob/main/android/session-core

This makes the initial route concrete: audit whether Jacket can integrate the proot-engine and session-core libraries, then run the official ARM64 Debian rootfs and .NET ASP.NET Core 10 runtime from Jacket's own private storage. The PRoot dependency is a candidate, not an approved dependency or a verified build yet.

## Candidate runtime layout

    Jacket UI
      └── LampacRuntimeManager
           ├── install / verify / update runtime in app-private storage
           ├── supervise one child process and collect bounded logs
           └── PRoot Android engine
                └── Debian ARM64 rootfs
                     └── .NET ASP.NET Core 10 runtime
                          └── Lampac Core.dll
                               └── 127.0.0.1:9118
                                    └── existing LampacBridgeClient
                                         └── Jacket source picker / Media3

The Linux userspace and Lampac files are managed by Jacket, not by a separately installed terminal app. The first install may download runtime components, so the base APK can remain smaller; the UI must show download size, storage requirement, progress, version and failure reason.

## Reduced-feature first boot

The first proof of concept should start with a conservative configuration:

- Bind only to 127.0.0.1:9118; reject any configuration that exposes the embedded service on 0.0.0.0, a LAN address or the public internet.
- Store the root filesystem, runtime, Lampac binaries, config, plugins and logs under app-private filesDir, not shared storage.
- Disable optional Chromium / Firefox / Playwright and transcoding modules initially unless their native dependencies are deliberately included and tested.
- Keep the feature set explicit. A provider requiring a disabled browser or native transcoder must report “unsupported in embedded mode”; do not silently claim full upstream parity.
- Pin the Lampac release version and verify downloaded archives before extraction. Updates must preserve user configuration and roll back to the previous known-good version if health checks fail.
- Never forward Jacket account secrets to arbitrary provider origins. Keep the existing origin pinning and credential boundaries in the bridge.

The official sample configuration uses 0.0.0.0 for listening. Jacket must generate its own embedded configuration with 127.0.0.1, a private app data path and only the explicitly enabled modules.

## Go / no-go proof of concept

Do not start a large UI or APK-bundling effort until this test passes on a real ARM64 Android device, without installing Termux:

1. Build or integrate the PRoot Android engine in a small Jacket test flavor and run it under the normal Android app UID (no root).
2. Create and enter an ARM64 Debian rootfs from Jacket's private directory.
3. Install / stage the matching ASP.NET Core 10 runtime and verify dotnet --info.
4. Launch the upstream Lampac release with a minimal config and confirm it binds to 127.0.0.1:9118 only.
5. Verify /version?type=hash, the event handshake used by Jacket, and at least one provider route that does not require a browser.
6. Verify stop, restart, crash detection, log capture, update interruption and rollback.
7. Verify Android process-execution / PRoot restrictions, certificate trust, DNS, storage pressure, memory use, thermal behaviour and battery impact.
8. Check that no listening socket is reachable from another device on the Wi-Fi network.

**Go** only if the server starts reliably, stays loopback-only, passes the endpoint smoke tests and fits acceptable disk / memory limits. Otherwise stop the PRoot route and evaluate a smaller native Kotlin provider engine rather than layering more workarounds.

## Jacket implementation milestones after the proof of concept

### M0 — runtime feasibility
- Audit and pin a specific PRoot Android engine commit; review its full component licence map and build integration.
- Build a minimal ARM64 harness and repeatable Android 12+ device test.
- Record APK download impact, installed footprint, first-start time, peak memory and idle CPU.
- Decide exactly which upstream Lampac modules can be enabled.

### M1 — runtime manager
- Add a typed state machine: NOT_INSTALLED, DOWNLOADING, INSTALLING, STARTING, READY, STOPPING, FAILED.
- Add bounded process output, redacted logs, startup timeout, health probe, crash recovery and explicit stop.
- Make install/update operations atomic; never overwrite a working runtime in place.
- Add unit tests for state transitions, cancellation, version verification and rollback.

### M2 — in-app controls and lifecycle
- Add Settings controls for Install / Update, Start / Stop, status, version, storage use and diagnostics.
- Start on demand before a Lampac-backed search; avoid starting it on every ordinary Jacket launch.
- Keep the process alive only while a request or playback route depends on the local server. Stop it safely when no longer needed, subject to device background-execution limits.
- Use a foreground notification when Android requires one for an active long-running engine. Do not claim guaranteed all-day operation on every Android vendor.

### M3 — bridge integration
- Route the existing bridge to the managed loopback endpoint when embedded mode is enabled.
- Preserve the current remote-server option as a fallback for devices where embedded mode is unsupported.
- Expose provider-specific capability/errors, not just a generic “Lampac running” indicator.
- Reuse the existing source picker and Media3 player; do not add a second playback stack.

### M4 — compatibility and release
- Expand the enabled provider/module set only after endpoint tests and lawful account/access behaviour are verified.
- Test phones, tablets and Android TV separately, including low-memory devices and process recreation.
- Publish the source and notices for all redistributed components and complete AGPL-3.0-only and PRoot GPL licence compliance review.

## Acceptance criteria

Embedded mode is ready for release only when:

- The user can install, start, stop and update Lampac entirely from Jacket without Termux or another app.
- A clean install can bootstrap the runtime with progress and clear storage requirements.
- Jacket's health check confirms the expected Lampac version and local API response.
- The server listens exclusively on loopback; LAN devices cannot connect.
- The embedded source lookup feeds the existing result picker and Media3 path.
- Stop / crash / reboot / low-storage / interrupted-update cases recover without corrupting user configuration.
- Unsupported upstream providers are visibly identified; no full-compatibility claim is made without tests.
- Licence texts, source notices and corresponding-source distribution requirements are satisfied.

## Current implementation status

The repository currently has a remote Lampac API bridge only. The runtime manager, Debian userspace, embedded server process, install UI and physical-device validation are **not implemented yet**. An Android PRoot engine candidate has been identified and inspected, but it has not been integrated into Jacket or tested on a Jacket build. This design is the gate for the first runnable prototype, not a release claim.


## Goal

Make Lampac run inside Jacket on the Android phone, using Jacket's private storage and the same local API bridge. No separate Termux installation, PC, NAS or separate Lampac app. This is a personal-use development branch; no public APK or GitHub release is published.

## Implemented in this draft

- Pin the Android PRoot source as a Git submodule at `oonid/pr@3ba86577f1f393a5e4ff6a92b3d4de08bb35786f`.
- Add a lean `:proot-engine` Android library wrapper. It compiles only the upstream PRoot engine Kotlin sources, native PTY JNI code and ARM64 engine binaries. The upstream terminal UI, `:session-core` and `:termlib` are deliberately not imported.
- Add the module to Jacket and package PRoot's native executables in `nativeLibraryDir`, required by Android's W^X execution policy.
- Add `JacketProotHost` and `LampacProotBootstrap`: create app-private prefix/home/cache directories, check ARM64 engine files, and link `pr-cli` / `proot` to the native executables.
- Keep the existing `LampacBridgeClient` as the app-facing interface; the eventual embedded server must bind only to `127.0.0.1:9118`.
- Disable public APK artifact uploads and public GitHub Release publishing in the personal-use build workflows.

## Current limits — be explicit

This is not yet a working Lampac server. The branch currently prepares the PRoot engine and app-private filesystem only. It does not yet install Debian, stage the .NET runtime or Lampac payload, start/stop the server, add a foreground service or provide a settings UI. No phone-side PRoot session or media playback has been verified.

The initial PRoot module is ARM64-only. Other Jacket ABIs must continue to use a remote Lampac address or show embedded mode as unsupported.

The upstream PRoot project documents targetSdk 35 compatibility. Jacket currently targets API 36, so PRoot must be validated on the actual target SDK and a physical Android 12 device before relying on the engine.

## Payload and storage

The current official Lampac release is 1.90.2. Its `lampac-nextgen.zip` asset is 480,375,787 bytes before extraction. The app must not silently download this blindly as the first setup step. The next implementation milestone is a reduced, integrity-checked ARM64 install profile that excludes browser/transcoding assets when they are disabled, reports exact download/storage sizes, and stages files transactionally.

Use the current supported ASP.NET Core 10 runtime for ARM64 Linux, pinned by version and SHA-256. Do not invoke the upstream `install.sh` directly: it creates a system user, writes to system paths and registers systemd, which is not appropriate inside Jacket's app-private PRoot rootfs.

The embedded first-run config must set `listen.ip=127.0.0.1`, `listen.port=9118`, and `chromium.enable=false`; browser-dependent and transcoding modules remain disabled until separately tested.

## Licence / distribution

Lampac NextGen is AGPL-3.0-only. The PRoot Android wrapper is MIT, but the native PRoot and `pr-cli` components are GPL-2.0-or-later / GPL-3.0-or-later. Keep the corresponding notices and source provenance. This work is for the user's private build; the workflow no longer publishes APK files or creates a public Release.

## Validation gate

1. Android CI compiles and tests the new PRoot module and existing Jacket app.
2. On the actual ARM64 Android 12 handset, run `pr-cli install debian` from Jacket's app-private storage without root or Termux.
3. Verify `dotnet --info` inside Debian using ASP.NET Core Runtime 10.
4. Install a reduced Lampac 1.90.2 payload and launch `Core.dll` with loopback-only binding.
5. Verify `/online.js`, `/lite/events`, `/lifeevents`, at least one non-browser provider and playback through the existing Jacket bridge.
6. Test stop/restart, app process death, reboot behaviour, Android power restrictions, disk use, RAM and battery drain.

Do not mark embedded Lampac complete until the device-side steps pass.
