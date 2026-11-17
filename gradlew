#!/usr/bin/env sh
# Lightweight Gradle bootstrapper for this source package.
# Downloads the pinned Gradle distribution when it is not already installed.
set -eu

GRADLE_VERSION="9.5.0"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/jacket-bootstrap/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME_DIR/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  BASE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/jacket-bootstrap"
  ZIP_FILE="$BASE_DIR/gradle-$GRADLE_VERSION-bin.zip"
  mkdir -p "$BASE_DIR"
  if [ ! -f "$ZIP_FILE" ]; then
    URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    if command -v curl >/dev/null 2>&1; then
      curl -fL "$URL" -o "$ZIP_FILE"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP_FILE" "$URL"
    else
      echo "Install curl or wget to download Gradle." >&2
      exit 1
    fi
  fi
  unzip -q -o "$ZIP_FILE" -d "$BASE_DIR"
fi

exec "$GRADLE_BIN" "$@"
