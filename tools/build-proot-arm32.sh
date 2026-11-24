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
git -C "$PR_ROOT" config url."https://github.com/".insteadOf "git@github.com:"
git -C "$PR_ROOT" submodule update --init vendor/samba

"$PR_ROOT/scripts/build.sh" --arch=arm --ndk-path="$NDK"

RUST_TARGET="armv7-linux-androideabi"
RUST_LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi28-clang"
rustup target add "$RUST_TARGET"
(
  cd "$PR_ROOT/src/pr-cli"
  CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$RUST_LINKER" \
    cargo build --release --target "$RUST_TARGET"
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
