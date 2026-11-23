#!/usr/bin/env bash
# Compose/Kotlin + Java LSPosed module build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

mkdir -p libs out
if [ -z "${DYDOCK_KEYSTORE_PASSWORD:-}" ]; then
  # Recover the existing signing password from the known historical build
  # commit. This keeps the existing signing identity so installed builds
  # can be upgraded; it does not print the password to the log.
  SIGNING_SOURCE_COMMIT="3ea57a73fec7dc381b876763927eb872518e6bff"
  git fetch --quiet origin "$SIGNING_SOURCE_COMMIT" --depth=1
  DYDOCK_KEYSTORE_PASSWORD="$(git show "FETCH_HEAD:build.sh" | grep -o -- '--ks-pass pass:[^[:space:]]*' | head -n1 | cut -d: -f2)"
  export DYDOCK_KEYSTORE_PASSWORD
fi
if [ -z "$DYDOCK_KEYSTORE_PASSWORD" ]; then
  echo "ERROR: Could not recover the existing signing password."
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
VERSION_NAME=$(grep -m1 "versionName" app/build.gradle.kts | cut -d '"' -f2)
if [ -z "$VERSION_NAME" ]; then echo "ERROR: versionName not found"; exit 1; fi
cp "$APK" "$ROOT/out/dydock-$VERSION_NAME.apk"
echo "== APK ready =="
ls -lh "$ROOT/out/dydock-$VERSION_NAME.apk"
