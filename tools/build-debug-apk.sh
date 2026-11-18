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
# Signs with the standard Android debug keystore (~/.android/debug.keystore).
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

mkdir -p "$out/classes" "$out/stage"

nice -n 20 "$ndk/ndk-build" -j4 NDK_PROJECT_PATH=null \
  APP_BUILD_SCRIPT="$main/jni/Android.mk" NDK_APPLICATION_MK="$main/jni/Application.mk" \
  APP_ABI="$abis" APP_PLATFORM=android-21 NDK_OUT="$out/obj" NDK_LIBS_OUT="$out/stage/lib"

javac -source 8 -target 8 -Xlint:-options -cp "$jar" -d "$out/classes" "$main"/java/com/ampixa/sanotts/tts/*.java
"$bt/d8" --min-api 21 --lib "$jar" --output "$out/stage" "$out"/classes/com/ampixa/sanotts/tts/*.class

# Gradle injects the package from `namespace`; aapt2 needs it in the manifest.
sed "s|<manifest |<manifest package=\"$pkg\" |" "$main/AndroidManifest.xml" | tee "$out/AndroidManifest.xml" | grep -q "package=\"$pkg\""
"$bt/aapt2" compile --dir "$main/res" -o "$out/res.zip"
"$bt/aapt2" link -o "$out/unaligned.apk" -I "$jar" --manifest "$out/AndroidManifest.xml" \
  -A "$main/assets" --min-sdk-version 21 --target-sdk-version 34 \
  --version-code 1 --version-name 0.1.0 --debug-mode "$out/res.zip"
(cd "$out/stage" && zip -q -r "$out/unaligned.apk" classes.dex && zip -q -r -0 "$out/unaligned.apk" lib -i '*.so')
"$bt/zipalign" -p -f 4 "$out/unaligned.apk" "$out/sanotts-debug.apk"
rm -f "$out/unaligned.apk" "$out/res.zip"
"$bt/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android "$out/sanotts-debug.apk"
"$bt/apksigner" verify "$out/sanotts-debug.apk"
ls -l "$out/sanotts-debug.apk"
