#!/bin/bash
set -e

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
ROOT="$(cd "$(dirname "$0")" && pwd)"
B="$ROOT/build"

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

rm -rf "$B"
mkdir -p "$B/obj" "$B/dex"

echo ">>> 1/6 编译资源"
"$AAPT2" compile --dir "$ROOT/res" -o "$B/res.zip"

echo ">>> 2/6 链接资源并生成 R.java"
"$AAPT2" link \
  -o "$B/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$B/obj" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  "$B/res.zip"

echo ">>> 3/6 编译 Java"
javac -nowarn -source 8 -target 8 \
  -classpath "$PLATFORM" \
  -d "$B/obj" \
  "$B/obj/com/toy/tvlock/R.java" \
  "$ROOT/src/com/toy/tvlock/"*.java 2>&1 | grep -v "bootstrap class path" || true

echo ">>> 4/6 转 dex"
"$D8" --lib "$PLATFORM" --min-api 21 --output "$B/dex" $(find "$B/obj" -name "*.class")

echo ">>> 5/6 打包 dex"
cp "$B/base.apk" "$B/unsigned.apk"
cd "$B/dex" && zip -q "$B/unsigned.apk" classes.dex

echo ">>> 6/6 对齐并签名"
"$ZIPALIGN" -f 4 "$B/unsigned.apk" "$B/aligned.apk"

if [ ! -f "$ROOT/tvlock.keystore" ]; then
  keytool -genkeypair -v -keystore "$ROOT/tvlock.keystore" -alias tvlock \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass tvlock123 -keypass tvlock123 \
    -dname "CN=TVLock, OU=Home, O=Home, L=Shanghai, C=CN" >/dev/null 2>&1
fi

"$APKSIGNER" sign \
  --ks "$ROOT/tvlock.keystore" \
  --ks-pass pass:tvlock123 \
  --key-pass pass:tvlock123 \
  --out "$B/tvlock.apk" \
  "$B/aligned.apk"

echo ""
echo "=== 构建完成 ==="
ls -lh "$B/tvlock.apk"
"$APKSIGNER" verify --print-certs "$B/tvlock.apk" | head -3
