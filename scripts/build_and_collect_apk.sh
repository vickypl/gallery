#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_DIR="$ROOT_DIR/apk"
OUTPUT_GLOB="$ROOT_DIR/app/build/outputs/apk"

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

TARGET_APK="$APK_DIR/gallery-${BUILD_TYPE}-latest.apk"
cp -f "$LATEST_APK" "$TARGET_APK"

echo "APK copied to: $TARGET_APK"
