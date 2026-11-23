# Experimental Lampa UI integration

## Source and build

The upstream Lampa source is kept verbatim in `third_party/lampa-source/` at commit
`e583e42238587b2b85afa9f386e9b41adfc16f35`. Jacket-specific WebView glue lives
outside that directory.

The APK build uses Node.js 22. Install the upstream build dependencies once:

```bash
cd third_party/lampa-source
npm install --no-package-lock --no-audit --no-fund
cd ../..
node tools/build-lampa-web.mjs
```

The Gradle `preBuild` task invokes the asset builder automatically. CI installs
Node dependencies before Gradle and then builds the same local assets.

The build bundles `src/app.js` with the upstream Rollup/Babel plugins, compiles
the upstream Sass entry point, copies `public/` to
`app/src/main/assets/lampa/`, and adds Jacket's separate
`lampa-android/modification.js`. The upstream source files are not edited.

## Runtime routing

The Lampa WebView loads its bundled assets under the synthetic local origin
`http://127.0.0.1:9118/app/`. Requests in that path are served from APK assets by
the WebView client; other paths on the same origin are allowed to reach the
embedded Lampac server. This lets the Lampa plugin load
`http://127.0.0.1:9118/online.js` without enabling mixed-content HTTP access to
arbitrary public sites.

Jacket's modification script retries the Lampac plugin while the local server
is stopped and loads it once `/online.js` becomes available. Starting Lampac
does not require reinstalling the APK.

Playback stays inside Lampa: the WebView deliberately does not identify itself
with Lampa's `lampa_client` marker, so Lampa selects its own HTML5/WebView
player rather than handing streams to Jacket's Media3/ExoPlayer screen. The
Jacket player remains available for Jacket's own catalog and torrent flows,
but Lampa playback is not routed through it.

The limited `AndroidJS` bridge handles direct magnet / `.torrent` links,
exit and YouTube handoff only. Torrent links are restricted to valid magnets
or HTTPS / loopback `.torrent` files. The bridge does not expose an
`openPlayer` method.

Lampa's remote account synchronization, plugin store and socket service are
disabled in this initial embedded profile. The local Lampac plugin remains
loaded from Jacket's own embedded server. The bridge does not advertise native
HTTP-request support, so unsupported native-only Lampa features may use their
web fallback or remain unavailable.

## Validation status and known limits

This is an experimental first integration, not yet an accepted release:

- CI must verify Node dependency installation, Lampa bundling, Android lint,
  unit tests and APK assembly.
- Lampa's built-in WebView player, torrent handoff and provider discovery need
  physical tests on the Android 12 phone.
- Android TV remote navigation, WebView memory use, resume after process death,
  and mixed long-running video sessions also need device acceptance tests.
- Lampa's catalog and images still use the upstream public metadata services;
  this is not an offline catalog.
- Lampa and Lampac are separate upstream components. Keep their source notices
  and review dependency licenses before distributing a combined build.

Do not merge this experiment into `main` or replace the installed APK until
the CI checks and physical-device tests pass.
