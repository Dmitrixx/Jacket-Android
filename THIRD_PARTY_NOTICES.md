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