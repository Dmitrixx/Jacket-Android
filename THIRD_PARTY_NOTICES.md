# Third-party source and licence notices

## Lampa

- Source: https://github.com/yumata/lampa-source
- Pinned commit: e583e42238587b2b85afa9f386e9b41adfc16f35
- Complete source: third_party/lampa-source/
- Licence: upstream LICENSE (GPL-2.0).

The Android build compiles the upstream Lampa web client and adds only a loopback Lampac URL plus an Android WebView bridge script. Upstream copyright notices and licence files are retained.

## Lampac NextGen

- Source: https://github.com/lampac-nextgen/lampac
- Pinned release: 1.90.2
- Release source commit: 7941d27e02d1ddcab242baaae92577fbd6140521
- Complete source: third_party/lampac-source/
- Licence: AGPL-3.0-only; see the upstream LICENSE.

The installed server comes from the official full lampac-nextgen.zip release asset and is SHA-256 verified before extraction. The installer does not filter module directories.

## PRoot

- Source: https://github.com/oonid/pr
- Pinned commit: 3ba86577f1f393a5e4ff6a92b3d4de08bb35786f
- Source: third_party/oonid-pr/
- The upstream wrapper and native components retain their MIT / GPL notices.

## Android and web build dependencies

AndroidX, Gradle, Node.js, Rollup, Babel, Sass, Autoprefixer and other build dependencies retain their upstream licences. Node dependencies are installed during the build and are not copied into the source repositories as vendored package files.

This is a private personal-use build. Any redistribution or network deployment must be reviewed against GPL and AGPL obligations, including source and licence-notice requirements.

## Integrated DPI bypass engine

- [hufrea/byedpi](https://github.com/hufrea/byedpi), pinned commit `ba532298de7b28cfe854aea83d061369d13ca290`, licence: MIT.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), pinned commit `4d6c334dbfb68a79d1970c2744e62d09f71df12f`, licence: MIT.
- Complete sources: `third_party/byedpi/` and `third_party/hev-socks5-tunnel/`.

The Lampa APK compiles the permissively licensed native ByeDPI engine and HEV tunnel directly into its own package. The Kotlin settings screen, service lifecycle, VPN integration, and native JNI bridge are implemented in this repository. Users do not need to install a second application.

The original [ByeByeDPI Android frontend](https://github.com/romanvht/ByeByeDPI) is GPL-3.0-only and is not copied or linked into the GPL-2.0 Lampa client. Only the independently licensed MIT native engines are used; their upstream copyright and licence notices are retained in the submodules.
