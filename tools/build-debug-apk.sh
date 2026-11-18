#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Debug APK without Gradle: ndk-build + javac + aapt2 + d8 + zipalign +
# apksigner. This is the build that produced the APK described in the README;
# use ./gradlew assembleDebug once that path has been exercised.
#
#   ANDROID_HOME=<sdk with ndk/27.2.12479018, platforms/android-34,
#                 build-tools/34.0.0> tools/build-debug-apk.sh <out dir> [abi...]
#
# Signs with the standard Android debug keystore (~/.android/debug.keystore),
# or the one named by DEBUG_KEYSTORE. An update only installs over an earlier
# build signed with the same keystore.
#
# PREBUILT_LIB_DIR=<dir holding <abi>/libsanotts_android.so> skips ndk-build
# and packages those libraries instead. Only for rebuilding the Java side when
# the native sources are unchanged since the libraries were built.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:?usage: build-debug-apk.sh <out dir> [abi...]}"
shift
abis="${*:-arm64-v8a}"
sdk="${ANDROID_HOME:?set ANDROID_HOME}"
ndk="$sdk/ndk/27.2.12479018"
bt="$sdk/build-tools/34.0.0"
jar="$sdk/platforms/android-34/android.jar"
pkg=com.ampixa.sanotts.tts
main="$root/app/src/main"
debug="$root/app/src/debug"

mkdir -p "$out/classes" "$out/stage"

if [ -n "${PREBUILT_LIB_DIR:-}" ]; then
  for abi in $abis; do
    mkdir -p "$out/stage/lib/$abi"
    cp "$PREBUILT_LIB_DIR/$abi/libsanotts_android.so" "$out/stage/lib/$abi/"
  done
else
  nice -n 20 "$ndk/ndk-build" -j4 NDK_PROJECT_PATH=null \
    APP_BUILD_SCRIPT="$main/jni/Android.mk" NDK_APPLICATION_MK="$main/jni/Application.mk" \
    APP_ABI="$abis" APP_PLATFORM=android-21 NDK_OUT="$out/obj" NDK_LIBS_OUT="$out/stage/lib"
fi

javac -source 8 -target 8 -Xlint:-options -cp "$jar" -d "$out/classes" \
  "$main"/java/com/ampixa/sanotts/tts/*.java "$debug"/java/com/ampixa/sanotts/tts/*.java
"$bt/d8" --min-api 21 --lib "$jar" --output "$out/stage" "$out"/classes/com/ampixa/sanotts/tts/*.class

# Gradle injects the package from `namespace` and merges the debug manifest;
# aapt2 does neither, so both are done here.
awk -v pkg="$pkg" -v dbg="$debug/AndroidManifest.xml" '
  BEGIN {
    while ((getline line < dbg) > 0) {
      if (line ~ /<activity/) on = 1
      if (on) extra = extra line "\n"
      if (line ~ /\/>/) on = 0
    }
  }
  /<manifest / { sub(/<manifest /, "<manifest package=\"" pkg "\" ") }
  /<\/application>/ { printf "%s", extra }
  { print }' "$main/AndroidManifest.xml" | tee "$out/AndroidManifest.xml" | grep -q "package=\"$pkg\""
grep -q SpeakTestActivity "$out/AndroidManifest.xml"
"$bt/aapt2" compile --dir "$main/res" -o "$out/res.zip"
"$bt/aapt2" link -o "$out/unaligned.apk" -I "$jar" --manifest "$out/AndroidManifest.xml" \
  -A "$main/assets" --min-sdk-version 21 --target-sdk-version 34 \
  --version-code 2 --version-name 0.2.0 --debug-mode "$out/res.zip"
(cd "$out/stage" && zip -q -r "$out/unaligned.apk" classes.dex && zip -q -r -0 "$out/unaligned.apk" lib -i '*.so')
"$bt/zipalign" -p -f 4 "$out/unaligned.apk" "$out/sanotts-debug.apk"
rm -f "$out/unaligned.apk" "$out/res.zip"
"$bt/apksigner" sign --ks "${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}" --ks-pass pass:android "$out/sanotts-debug.apk"
"$bt/apksigner" verify "$out/sanotts-debug.apk"
ls -l "$out/sanotts-debug.apk"
