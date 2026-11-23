#!/usr/bin/env bash
# 离线编译：只用 javac + aapt2 + d8 + apksigner，不依赖 Gradle / Compose。
#
# 用法：
#   DYDOCK_KEYSTORE_PASSWORD=xxxx bash build-local.sh
#
# 产物：out/dydock-<version>.apk（与 CI 用同一份 keystore 签名）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/deepbot-toolchain/android-sdk}}"
PLATFORM="${DYDOCK_PLATFORM:-$SDK/platforms/android-37.0/android.jar}"
BUILD_TOOLS="${DYDOCK_BUILD_TOOLS:-$SDK/build-tools/37.0.0}"
[ -f "$PLATFORM" ] || { echo "ERROR: android.jar not found: $PLATFORM"; exit 1; }
[ -x "$BUILD_TOOLS/d8" ] || { echo "ERROR: build-tools not found: $BUILD_TOOLS"; exit 1; }

if [ -z "${DYDOCK_KEYSTORE_PASSWORD:-}" ]; then
  SIGNING_SOURCE_COMMIT="3ea57a73fec7dc381b876763927eb872518e6bff"
  git fetch --quiet origin "$SIGNING_SOURCE_COMMIT" --depth=1
  DYDOCK_KEYSTORE_PASSWORD="$(git show "FETCH_HEAD:build.sh" | grep -o -- '--ks-pass pass:[^[:space:]]*' | head -n1 | cut -d: -f2)"
fi
[ -n "${DYDOCK_KEYSTORE_PASSWORD:-}" ] || { echo "ERROR: missing keystore password"; exit 1; }

if [ ! -f libs/api-82.jar ]; then
  mkdir -p libs
  curl -fsSL -o libs/api-82.jar https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
fi

VERSION_NAME=$(grep -m1 "versionName" app/build.gradle.kts | cut -d '"' -f2)
VERSION_CODE=$(grep -m1 "versionCode" app/build.gradle.kts | grep -o '[0-9]\+')
[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || { echo "ERROR: version info not found"; exit 1; }

WORK="build/local"
rm -rf "$WORK"
mkdir -p "$WORK/obj" "$WORK/dex" out

echo "== aapt2 compile/link =="
"$BUILD_TOOLS/aapt2" compile --dir app/res -o "$WORK/res.zip"
"$BUILD_TOOLS/aapt2" link -o "$WORK/base.apk" \
  -I "$PLATFORM" \
  --manifest app/AndroidManifest.xml \
  -A app/assets \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$WORK/res.zip"

echo "== javac =="
find src -name '*.java' > "$WORK/sources.txt"
javac -source 17 -target 17 -nowarn -encoding UTF-8 \
  -classpath "$PLATFORM:libs/api-82.jar" \
  -d "$WORK/obj" @"$WORK/sources.txt"

echo "== d8 =="
find "$WORK/obj" -name '*.class' > "$WORK/classes.txt"
"$BUILD_TOOLS/d8" --release --min-api 26 --lib "$PLATFORM" \
  --output "$WORK/dex" @"$WORK/classes.txt"

echo "== package =="
APK="$ROOT/$WORK/unsigned.apk"
cp "$WORK/base.apk" "$APK"
(cd "$WORK/dex" && zip -q -X "$APK" classes.dex)
(cd "$ROOT/app" && zip -q -X "$APK" META-INF/xposed/module.prop META-INF/xposed/scope.list)

"$BUILD_TOOLS/zipalign" -f -p 4 "$APK" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks keystore/dydock.jks --ks-key-alias dydock \
  --ks-pass "pass:$DYDOCK_KEYSTORE_PASSWORD" \
  --key-pass "pass:$DYDOCK_KEYSTORE_PASSWORD" \
  --out "out/dydock-$VERSION_NAME.apk" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --print-certs "out/dydock-$VERSION_NAME.apk" | head -3

echo "== APK ready =="
ls -lh "out/dydock-$VERSION_NAME.apk"
