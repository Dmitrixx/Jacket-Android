# Embedded Lampac runtime on Android — feasibility and implementation plan

**Status:** feasibility design / implementation gate. This document does not claim that Lampac is already embedded, that an Android runtime has been built, or that the design has passed a physical-device test.

## Product target

A user installs one Jacket APK. From **Settings → Lampac engine**, they can install or update the engine, start/stop it, inspect status and logs, and enable automatic start when a Lampac-backed search is requested. No separate Termux, Debian app, NAS, PC, Jackett, or TorrServer installation is required.

The existing LampacBridgeClient remains the app-facing API client. The embedded engine should expose the same local API contract so the source picker and Media3 playback pipeline do not need a second implementation.

## Current facts and constraints

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

## Candidate implementation

### First candidate: app-managed Linux userspace (no Termux app)

Run the upstream ARM64 Lampac release inside an app-private Debian userspace, launched and supervised by Jacket. A PRoot-style userspace runner is the first candidate because it may allow the existing Linux ASP.NET Core runtime to run without device root. It is **not yet proven** to work from Jacket's Android sandbox.

    Jacket UI
      └── LampacRuntimeManager
           ├── install / verify / update engine files in app-private storage
           ├── start / stop / monitor child process
           └── Lampac runtime service
                └── userspace runner
                     └── Debian ARM64 rootfs
                          └── .NET ASP.NET Core 10 runtime
                               └── Lampac Core.dll
                                    └── 127.0.0.1:9118
                                         └── existing LampacBridgeClient
                                              └── Jacket source picker / Media3

This would be integrated into the Jacket experience: the Linux userspace and Lampac files are managed by Jacket, not by a separately installed terminal app. The first install may download runtime components, so the base APK can remain smaller; the UI must show download size, storage requirement, progress, version and failure reason.

### Reduced-feature first boot

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

1. Package or stage the userspace runner in Jacket's private app directory and execute it under the normal Android app UID (no root).
2. Create and enter an ARM64 Debian rootfs from that private directory.
3. Install / stage the matching ASP.NET Core 10 runtime and verify dotnet --info.
4. Launch the upstream Lampac release with a minimal config and confirm it binds to 127.0.0.1:9118 only.
5. Verify /version?type=hash, the event handshake used by Jacket, and at least one provider route that does not require a browser.
6. Verify stop, restart, crash detection, log capture, update interruption and rollback.
7. Verify Android process-execution / PRoot restrictions, certificate trust, DNS, storage pressure, memory use, thermal behaviour and battery impact.
8. Check that no listening socket is reachable from another device on the Wi-Fi network.

**Go** only if the server starts reliably, stays loopback-only, passes the endpoint smoke tests and fits acceptable disk / memory limits. Otherwise stop the PRoot route and evaluate a smaller native Kotlin provider engine rather than layering more workarounds.

## Jacket implementation milestones after the proof of concept

### M0 — runtime feasibility
- Minimal ARM64 harness and repeatable Android 12+ device test.
- Record APK download impact, installed footprint, first-start time, peak memory and idle CPU.
- Decide exactly which upstream modules can be enabled.

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
- Publish the source and notices for all redistributed components and complete the AGPL-3.0-only compliance review.

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

The repository currently has a remote Lampac API bridge only. The runtime manager, Debian userspace, embedded server process, install UI and physical-device validation are **not implemented yet**. This design is the gate for the first runnable prototype, not a release claim.
