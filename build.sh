#!/usr/bin/env bash
# dydock 构建：只要 JDK + Android SDK（build-tools + platforms），不需要 Gradle。
#   ANDROID_HOME=~/android-sdk bash build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"

BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)"
[ -n "${BT:-}" ] && [ -n "${PLATFORM:-}" ] || { echo "找不到 SDK"; exit 1; }

AJAR="$PLATFORM/android.jar"
VER_NAME="$(grep -o 'versionName="[^"]*"' "$ROOT/app/AndroidManifest.xml" | cut -d'"' -f2)"
VER_CODE="$(grep -o 'versionCode="[^"]*"' "$ROOT/app/AndroidManifest.xml" | cut -d'"' -f2)"

echo "build-tools: $BT"
echo "platform   : $PLATFORM"
echo "版本       : $VER_NAME ($VER_CODE)"

mkdir -p "$ROOT/libs"
if [ ! -f "$ROOT/libs/api-82.jar" ]; then
  echo "== 下载 Xposed API =="
  curl -fsSL -o "$ROOT/libs/api-82.jar" \
    https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
fi

cd "$ROOT"
rm -rf build out
mkdir -p build/classes build/dex build/res out

echo "== javac =="
javac --release 11 -nowarn -encoding UTF-8 \
  -classpath "$AJAR:$ROOT/libs/api-82.jar" \
  -d build/classes $(find src -name '*.java')

echo "== jar + d8 =="
jar cf build/classes.jar -C build/classes .
"$BT/d8" --lib "$AJAR" --min-api 26 --output build/dex build/classes.jar

echo "== aapt2 =="
if [ -d app/res ]; then
  "$BT/aapt2" compile --dir app/res -o build/res/res.zip
  "$BT/aapt2" link -o build/base.apk -I "$AJAR" --manifest app/AndroidManifest.xml \
    -A app/assets --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VER_CODE" --version-name "$VER_NAME" build/res/res.zip
else
  "$BT/aapt2" link -o build/base.apk -I "$AJAR" --manifest app/AndroidManifest.xml \
    -A app/assets --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VER_CODE" --version-name "$VER_NAME"
fi

echo "== 打包 =="
cp build/base.apk build/unsigned.apk
( cd build/dex && zip -q -X ../unsigned.apk classes.dex )
( cd app && zip -q -X ../build/unsigned.apk META-INF/xposed/module.prop META-INF/xposed/scope.list )

echo "== 对齐 + 签名 =="
KS="$ROOT/keystore/dydock.jks"
if [ ! -f "$KS" ]; then
  mkdir -p "$ROOT/keystore"
  keytool -genkeypair -keystore "$KS" \
    -storepass dydock2026 -keypass dydock2026 \
    -alias dydock -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=dydock, OU=dydock, O=dydock, L=NA, ST=NA, C=CN" >/dev/null 2>&1
  echo "生成了新签名 keystore/dydock.jks（务必提交进仓库，否则以后装不上）"
fi
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" \
  --ks-pass pass:dydock2026 --key-pass pass:dydock2026 \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$ROOT/out/dydock-${VER_NAME}.apk" build/aligned.apk

echo "== 校验 =="
"$BT/apksigner" verify --print-certs "$ROOT/out/dydock-${VER_NAME}.apk" | head -4
ls -la "$ROOT/out"
echo "== 完成 =="
