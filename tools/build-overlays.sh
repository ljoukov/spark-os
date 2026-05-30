#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/home/liudmila/android-toolchain/sdk}"
JAVA_HOME="${JAVA_HOME:-/home/liudmila/android-toolchain/jdk}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-30/android.jar"
KEYSTORE="${SPARK_KEYSTORE:-$ROOT/apps/spark-kiosk/spark-debug.keystore}"

AAPT2="$BUILD_TOOLS/aapt2"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
KEYTOOL="$JAVA_HOME/bin/keytool"

SYSTEMUI_APK="${SPARK_SYSTEMUI_APK:-/home/liudmila/spark-rom-work/apks/SystemUI.apk}"
LATINIME_APK="${SPARK_LATINIME_APK:-/home/liudmila/spark-rom-work/apks/LatinIME.apk}"

ensure_keystore() {
  if [[ -f "$KEYSTORE" ]]; then
    return
  fi
  mkdir -p "$(dirname "$KEYSTORE")"
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass sparkpass \
    -keypass sparkpass \
    -alias spark \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Spark AI,O=Eviworld,C=US"
}

build_overlay() {
  local name="$1"
  local dir="$2"
  local target_apk="${3:-}"
  local out="$dir/build"

  rm -rf "$out"
  mkdir -p "$out"

  "$AAPT2" compile --dir "$dir/res" -o "$out/resources.zip"

  local link_args=(-I "$PLATFORM")
  if [[ -n "$target_apk" ]]; then
    if [[ ! -f "$target_apk" ]]; then
      echo "Missing target APK: $target_apk" >&2
      return 1
    fi
    link_args+=(-I "$target_apk")
  fi

  "$AAPT2" link \
    "${link_args[@]}" \
    --manifest "$dir/AndroidManifest.xml" \
    --no-resource-removal \
    --auto-add-overlay \
    -o "$out/$name-unsigned.apk" \
    "$out/resources.zip"

  "$ZIPALIGN" -f 4 "$out/$name-unsigned.apk" "$out/$name-aligned.apk"
  "$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:sparkpass \
    --key-pass pass:sparkpass \
    --out "$out/$name.apk" \
    "$out/$name-aligned.apk"
  "$APKSIGNER" verify --verbose "$out/$name.apk"
  echo "$out/$name.apk"
}

ensure_keystore
build_overlay SparkSystemBarsOverlay "$ROOT/overlays/system-bars"
build_overlay SparkSystemUIOverlay "$ROOT/overlays/systemui" "$SYSTEMUI_APK"
build_overlay SparkLatinIMEOverlay "$ROOT/overlays/latinime" "$LATINIME_APK"
