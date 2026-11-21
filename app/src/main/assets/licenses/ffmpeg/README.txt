Jacket includes the AndroidX Media3 FFmpeg audio decoder:
io.put:media3-ffmpeg-decoder:1.11.1+1

The Media3 wrapper is Apache-2.0. The bundled FFmpeg libraries are LGPL-2.1-or-later.
Enabled audio decoders include AC-3, E-AC-3, DTS, MLP and TrueHD (plus additional formats listed
in the upstream build). This build is version-matched to Media3 1.11.1.

Matching corresponding source and build materials:
https://repo1.maven.org/maven2/io/put/media3-ffmpeg-decoder/1.11.1+1/media3-ffmpeg-decoder-1.11.1+1-ffmpeg-source.tar.xz

Upstream project, build verification, exact FFmpeg/Media3 revisions and licence compliance checks:
https://github.com/putdotio/media3-ffmpeg-decoder

The AAR is statically linked. The corresponding source archive contains the FFmpeg source, the
LGPL-only patch, Media3 JNI wrapper and build scripts required to rebuild and relink the native
library. Users may replace the bundled libffmpegJNI.so with a compatible rebuild as allowed by
the LGPL. Codec patent rights are separate from software copyright licences.
