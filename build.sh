#!/usr/bin/env bash
# Compose/Kotlin + Java LSPosed module build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

mkdir -p libs out
if [ -z "${DYDOCK_KEYSTORE_PASSWORD:-}" ]; then
  echo "ERROR: DYDOCK_KEYSTORE_PASSWORD is not set."
  echo "Add it in GitHub: Settings > Secrets and variables > Actions."
  exit 1
fi

if [ ! -f libs/api-82.jar ]; then
  echo "== Download Xposed API 82 =="
  curl -fsSL -o libs/api-82.jar https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
fi

echo "== Gradle assembleRelease =="
gradle --no-daemon --stacktrace assembleRelease

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
test -s "$APK"
cp "$APK" "$ROOT/out/dydock-0.1.20.apk"
echo "== APK ready =="
ls -lh "$ROOT/out/dydock-0.1.20.apk"
