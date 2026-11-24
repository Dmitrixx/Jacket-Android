#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PR_ROOT="$ROOT/third_party/oonid-pr"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" || ! -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi28-clang" ]]; then
  echo "Android NDK r27c is required (ANDROID_NDK_HOME)." >&2
  exit 1
fi

# The pinned PRoot source can build Android ARMv7. Its checked-in native
# payload currently contains ARM64 only, so create the matching ARM32 files
# before Gradle packages the armeabi-v7a APK split.
# vendor/samba's .gitmodules entry uses SSH. Override the exact submodule
# URL in this checkout so a clean GitHub Actions runner never needs an SSH key.
git -C "$PR_ROOT" submodule sync -- vendor/samba
git -C "$PR_ROOT" config --local --replace-all submodule.vendor/samba.url \
  "https://github.com/samba-team/samba.git"
git -C "$PR_ROOT" submodule update --init vendor/samba

# The pinned upstream build script uses the compiler triple for the sysroot directory too.
# NDK r27c names the ARMv7 sysroot library directory arm-linux-androideabi,
# while the compiler executable remains armv7a-linux-androideabi28-clang.
python3 - "$PR_ROOT/scripts/build.sh" <<'PY'
from pathlib import Path
import sys

build_script = Path(sys.argv[1])
source = build_script.read_text()
old = '    cp -a "${ndk_sysroot}/usr/lib/${triple}" "${sysroot}/usr/lib/"'
new = '    cp -a "${ndk_sysroot}/usr/lib/arm-linux-androideabi" "${sysroot}/usr/lib/"'
if old not in source:
    raise SystemExit("Could not apply the ARMv7 NDK sysroot compatibility patch")
build_script.write_text(source.replace(old, new, 1))
PY

"$PR_ROOT/scripts/build.sh" --arch=arm --ndk-path="$NDK"

RUST_TARGET="armv7-linux-androideabi"
RUST_LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi28-clang"
rustup target add "$RUST_TARGET"

# pr-cli embeds the integration-test executable at compile time. Build the
# ARMv7 variant first and point the embedded include_bytes! at that artifact.
(
  cd "$PR_ROOT/src/proot-integration-test"
  CC="$RUST_LINKER" \
    CC_armv7_linux_androideabi="$RUST_LINKER" \
    AR="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar" \
    AR_armv7_linux_androideabi="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar" \
    CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$RUST_LINKER" \
    cargo build --release --target "$RUST_TARGET"
)

python3 - "$PR_ROOT/src/pr-cli/src/cmd_test.rs" "$RUST_TARGET" <<'PY'
from pathlib import Path
import sys

source_file = Path(sys.argv[1])
target = sys.argv[2]
source = source_file.read_text()
old = "proot-integration-test/target/aarch64-linux-android/release/proot-integration-test"
new = f"proot-integration-test/target/{target}/release/proot-integration-test"
if old not in source:
    raise SystemExit("Could not locate the embedded integration-test binary path")
source_file.write_text(source.replace(old, new, 1))
PY

(
  cd "$PR_ROOT/src/pr-cli"
  # Build only the runtime CLI. The upstream enosys_test helper is an
  # aarch64-only diagnostic binary and is not packaged into the Android app.
  CC="$RUST_LINKER" \
    CC_armv7_linux_androideabi="$RUST_LINKER" \
    AR="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar" \
    AR_armv7_linux_androideabi="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar" \
    CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$RUST_LINKER" \
    cargo build --release --target "$RUST_TARGET" --bin pr-cli
)

NATIVE_OUT="$PR_ROOT/android/proot-engine/src/main/jniLibs/armeabi-v7a"
mkdir -p "$NATIVE_OUT"
cp "$PR_ROOT/build/out/arm/proot" "$NATIVE_OUT/libproot.so"
test -s "$PR_ROOT/build/out/arm/loader"
cp "$PR_ROOT/build/out/arm/loader" "$NATIVE_OUT/libproot-loader.so"
cp "$PR_ROOT/src/pr-cli/target/$RUST_TARGET/release/pr-cli" "$NATIVE_OUT/libpr-cli.so"
chmod 755 "$NATIVE_OUT"/*.so

READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
for binary in "$NATIVE_OUT/libpr-cli.so" "$NATIVE_OUT/libproot.so" "$NATIVE_OUT/libproot-loader.so"; do
  "$READELF" -h "$binary" | grep -Eq 'Class:[[:space:]]+ELF32'
  "$READELF" -h "$binary" | grep -Eq 'Machine:[[:space:]]+ARM'
done

echo "ARMv7 PRoot runtime built and verified in $NATIVE_OUT"
