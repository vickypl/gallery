#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_DIR="$ROOT_DIR/apk"
OUTPUT_GLOB="$ROOT_DIR/app/build/outputs/apk"

ensure_compatible_java() {
  local major
  major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"

  if [[ -n "${major:-}" && "$major" -le 21 ]]; then
    return 0
  fi

  local mise_java17="$HOME/.local/share/mise/installs/java/17.0.2"
  if [[ -d "$mise_java17" ]]; then
    export JAVA_HOME="$mise_java17"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "Using JAVA_HOME=$JAVA_HOME for Android/Gradle compatibility"
    return 0
  fi

  echo "Warning: Java <= 21 is recommended for this build. Current runtime may fail."
}

mkdir -p "$APK_DIR"

BUILD_TYPE="${1:-debug}"
case "$BUILD_TYPE" in
  debug|release)
    ;;
  *)
    echo "Usage: $0 [debug|release]"
    exit 1
    ;;
esac

TASK_SUFFIX="$(tr '[:lower:]' '[:upper:]' <<< "${BUILD_TYPE:0:1}")${BUILD_TYPE:1}"
GRADLE_TASK=":app:assemble${TASK_SUFFIX}"

ensure_compatible_java

if [[ -x "$ROOT_DIR/gradlew" ]]; then
  (cd "$ROOT_DIR" && ./gradlew "$GRADLE_TASK")
else
  if command -v gradle >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && gradle "$GRADLE_TASK")
  else
    echo "Error: neither ./gradlew nor gradle is available. Build in Android Studio, then re-run this script."
    exit 2
  fi
fi

LATEST_APK="$(find "$OUTPUT_GLOB/$BUILD_TYPE" -maxdepth 1 -type f -name '*.apk' -printf '%T@ %p\n' | sort -nr | awk 'NR==1{print $2}')"

if [[ -z "${LATEST_APK:-}" ]]; then
  echo "Error: APK not found in $OUTPUT_GLOB/$BUILD_TYPE"
  exit 3
fi

TARGET_APK="$APK_DIR/gallery.apk"
cp -f "$LATEST_APK" "$TARGET_APK"

# Keep a variant-specific copy for historical/debug convenience.
cp -f "$LATEST_APK" "$APK_DIR/gallery-${BUILD_TYPE}-latest.apk"

echo "APK copied to: $TARGET_APK"
