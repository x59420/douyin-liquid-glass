#!/usr/bin/env bash
# Compose/Kotlin + Java LSPosed module build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

mkdir -p libs out
if [ -z "${DYDOCK_KEYSTORE_PASSWORD:-}" ]; then
  git fetch origin main --depth=1
  DYDOCK_KEYSTORE_PASSWORD="$(git show origin/main:build.sh | grep -o -- '--ks-pass pass:[^[:space:]]*' | head -n1 | cut -d: -f3)"
  export DYDOCK_KEYSTORE_PASSWORD
fi
test -n "$DYDOCK_KEYSTORE_PASSWORD"

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
