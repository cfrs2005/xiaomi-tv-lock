#!/bin/bash
# Off-device tests: PIN derivation, backoff policy, encoding.
# Runs on the local JVM. Does not touch a TV and does not need the Android SDK.
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build/test"

rm -rf "$OUT"
mkdir -p "$OUT"

javac -nowarn --release 11 -d "$OUT" \
  "$ROOT/src/com/toy/tvlock/PinCrypto.java" \
  "$ROOT/src/com/toy/tvlock/BackoffPolicy.java" \
  "$ROOT/test/com/toy/tvlock/RunTests.java"

java -cp "$OUT" com.toy.tvlock.RunTests
