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

MIN_SDK=26
TARGET_SDK=34

# The signing key must stay the same or the TV rejects the update with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE. The password is read from the environment; the fallback is
# the legacy value already baked into existing builds, and is flagged because it lives in history.
KS_PASS="${TVLOCK_KS_PASS:-tvlock123}"
if [ -z "${TVLOCK_KS_PASS:-}" ]; then
  echo ">>> 警告: 未设置 TVLOCK_KS_PASS，使用历史口令。请改用环境变量。"
fi

rm -rf "$B"
mkdir -p "$B/obj" "$B/dex"

echo ">>> 1/7 编译资源"
"$AAPT2" compile --dir "$ROOT/res" -o "$B/res.zip"

echo ">>> 2/7 链接资源并生成 R.java"
"$AAPT2" link \
  -o "$B/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$B/obj" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  "$B/res.zip"

echo ">>> 3/7 编译 Java"
javac -nowarn --release 11 \
  -classpath "$PLATFORM" \
  -d "$B/obj" \
  "$B/obj/com/toy/tvlock/R.java" \
  "$ROOT/src/com/toy/tvlock/"*.java

echo ">>> 4/7 转 dex"
"$D8" --lib "$PLATFORM" --min-api "$MIN_SDK" --output "$B/dex" $(find "$B/obj" -name "*.class")

echo ">>> 5/7 打包 dex"
cp "$B/base.apk" "$B/unsigned.apk"
cd "$B/dex" && zip -q "$B/unsigned.apk" classes.dex
cd "$ROOT"

echo ">>> 6/7 对齐并签名"
"$ZIPALIGN" -f 4 "$B/unsigned.apk" "$B/aligned.apk"

if [ ! -f "$ROOT/tvlock.keystore" ]; then
  keytool -genkeypair -v -keystore "$ROOT/tvlock.keystore" -alias tvlock \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=TVLock, OU=Home, O=Home, L=Shanghai, C=CN" >/dev/null 2>&1
fi

"$APKSIGNER" sign \
  --ks "$ROOT/tvlock.keystore" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --out "$B/tvlock.apk" \
  "$B/aligned.apk"

echo ">>> 7/7 校验产物"
"$APKSIGNER" verify "$B/tvlock.apk" >/dev/null
BADGING="$("$AAPT2" dump badging "$B/tvlock.apk")"
TREE="$("$AAPT2" dump xmltree "$B/tvlock.apk" --file AndroidManifest.xml)"

fail() { echo "!!! 校验失败: $1"; exit 1; }

echo "$BADGING" | grep -q "package: name='com.toy.tvlock'" || fail "包名不符"
echo "$BADGING" | grep -q "sdkVersion:'$MIN_SDK'" || fail "minSdk 不是 $MIN_SDK"
echo "$BADGING" | grep -q "targetSdkVersion:'$TARGET_SDK'" || fail "targetSdk 不是 $TARGET_SDK"
# The old bypass components must be gone for good.
echo "$TREE" | grep -q "LockService" && fail "LockService 仍在清单中"
echo "$TREE" | grep -q "AlarmReceiver" && fail "AlarmReceiver 仍在清单中"
echo "$TREE" | grep -q "RevivalListener" || fail "RevivalListener 未注册"
echo "$TREE" | grep -q "BIND_NOTIFICATION_LISTENER_SERVICE" || fail "通知监听缺少系统权限保护"

echo ""
echo "=== 构建完成 ==="
ls -lh "$B/tvlock.apk"
"$APKSIGNER" verify --print-certs "$B/tvlock.apk" | head -3
echo ""
echo "组件清单:"
echo "$BADGING" | grep -E "^(launchable-activity|provides-component|uses-permission)" | sed 's/^/  /'
