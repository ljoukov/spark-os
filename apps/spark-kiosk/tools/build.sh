#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/home/liudmila/android-toolchain/sdk}"
JAVA_HOME="${JAVA_HOME:-/home/liudmila/android-toolchain/jdk}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-30/android.jar"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"

OUT="$ROOT/build"
GEN="$OUT/generated"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
KEYSTORE="$ROOT/spark-debug.keystore"
APK_UNSIGNED="$OUT/spark-unsigned.apk"
APK_DEX="$OUT/spark-unsigned-dex.apk"
APK_ALIGNED="$OUT/spark-aligned.apk"
APK_FINAL="$OUT/spark-kiosk.apk"

rm -rf "$OUT"
mkdir -p "$GEN" "$CLASSES" "$DEX"

"$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/resources.zip"
"$AAPT2" link \
  -I "$PLATFORM" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --java "$GEN" \
  -o "$APK_UNSIGNED" \
  "$OUT/resources.zip"

mapfile -t SOURCES < <(find "$ROOT/app/src/main/java" "$GEN" -name '*.java' | sort)
"$JAVAC" -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$PLATFORM" \
  -d "$CLASSES" \
  "${SOURCES[@]}"

mapfile -t CLASSFILES < <(find "$CLASSES" -name '*.class' | sort)
"$D8" --min-api 30 --lib "$PLATFORM" --output "$DEX" "${CLASSFILES[@]}"

cp "$APK_UNSIGNED" "$APK_DEX"
(cd "$DEX" && zip -q "$APK_DEX" classes.dex)

"$ZIPALIGN" -f 4 "$APK_DEX" "$APK_ALIGNED"

if [[ ! -f "$KEYSTORE" ]]; then
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass sparkpass \
    -keypass sparkpass \
    -alias spark \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Spark AI,O=Eviworld,C=US"
fi

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:sparkpass \
  --key-pass pass:sparkpass \
  --out "$APK_FINAL" \
  "$APK_ALIGNED"

"$APKSIGNER" verify --verbose "$APK_FINAL"
echo "$APK_FINAL"
