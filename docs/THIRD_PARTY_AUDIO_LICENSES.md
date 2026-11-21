# Third-party audio decoder notices

Jacket uses the published AndroidX Media3 FFmpeg audio decoder:

- Maven coordinate: `io.put:media3-ffmpeg-decoder:1.11.1+1`
- Media3 version: `1.11.1`
- Wrapper/JNI licence: Apache-2.0.
- Bundled FFmpeg licence: LGPL-2.1-or-later.
- Upstream AAR build and licence checks: https://github.com/putdotio/media3-ffmpeg-decoder
- Exact matching FFmpeg source, LGPL-only patch, JNI wrapper, and build/relink instructions:
  https://repo1.maven.org/maven2/io/put/media3-ffmpeg-decoder/1.11.1+1/media3-ffmpeg-decoder-1.11.1+1-ffmpeg-source.tar.xz

The upstream build uses pinned Media3 and FFmpeg revisions, disables GPL/nonfree/version-3 components, and checks the built object files and published source archive. The AAR statically links FFmpeg into `libffmpegJNI.so`; it is not a dynamically linked FFmpeg package. The exact build manifest and third-party notice files travel inside the AAR.

The app ships user-readable copies of the upstream `NOTICE`, Apache `LICENSE`, FFmpeg `LICENSE.md` and LGPL 2.1 text under `app/src/main/assets/licenses/ffmpeg/`; they are available from Settings → About. Do not add broad Gradle resource excludes that remove `META-INF/media3-ffmpeg-decoder/**`.

This technical record is not legal advice. Before distributing signed production APKs, verify that the chosen distribution model continues to meet the applicable LGPL source, relinking, notice and reverse-engineering terms. Audio-format patent rights may be separate from the software licences.
