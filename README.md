# sanotts-android

An Android text-to-speech engine (`TextToSpeechService`) that exposes
[sanoTTS](https://github.com/Ampixa/sanoTTS) voices as a system voice, so
TalkBack and any other app using the Android `TextToSpeech` API can speak with
them. Synthesis is on-device; the app requests no permissions, including no
network permission.

**Status: pre-alpha. The APK builds but has never been run on an Android
device or emulator, and has never been used with TalkBack.** See
[Status](#status) before relying on anything here.

## What is in it

* `app/src/main/java/.../SanoTtsService.java`: the engine. Splits text at
  sentence and clause boundaries, synthesises one piece at a time and streams
  16-bit mono 22050 Hz PCM to the framework, checking for a stop request
  between pieces.
* `app/src/main/java/.../CheckVoiceData.java`: answers
  `android.speech.tts.engine.CHECK_TTS_DATA`.
* `app/src/main/jni/sanotts_android.c`: JNI glue over the upstream C, which is
  compiled unmodified from `third_party/sanotts/` (espeak-ng phonemizer,
  duration + acoustic model, decoder).
* `app/src/main/assets/`: espeak-ng data and the voices.
* `tests/`: host-side check of the same C, and of the JNI entry points on a
  desktop JVM, against upstream's WebAssembly build.

## Languages and voices

One voice per language, all 22.05 kHz:

| Language | Android locale | Voice (upstream key) | Weights | Size |
| --- | --- | --- | --- | ---: |
| English (US) | `eng-USA` | amy | float32 | 5.6 MB |
| German | `deu-DEU` | german | float16 | 3.0 MB |
| French | `fra-FRA` | french | float16 | 3.0 MB |
| Spanish | `spa-ESP` | spanish | float16 | 3.0 MB |
| Italian | `ita-ITA` | italian | float16 | 3.0 MB |
| Portuguese (Brazil) | `por-BRA` | portuguese | float16 | 3.0 MB |
| Romanian | `ron-ROU` | romanian | float16 | 3.0 MB |
| Czech | `ces-CZE` | czech | float16 | 3.0 MB |
| Turkish | `tur-TUR` | turkish | float16 | 3.0 MB |
| Polish | `pol-POL` | polish | float16 | 3.0 MB |
| Vietnamese | `vie-VNM` | vietnamese | float32 | 6.0 MB |
| Indonesian | `ind-IDN` | indonesian | float32 | 6.0 MB |

"Ships" here means: for each of these, the C in this repository produces
phoneme ids identical to upstream's WebAssembly build and audio that matches
it (see [Status](#status)). It does not mean the voice has been heard on a
phone. Upstream's README has word-error-rate figures for most of these voices
(Polish has none) and notes that no listening test has been run on any
non-English voice.

Not included, and why:

* **Russian**: needs a stress lexicon that upstream assembles per utterance in
  JavaScript; the full dictionary is 8.2 MB and is not in upstream's data set.
* **Arabic**: needs upstream's diacritizer, which exists only as a JavaScript
  port.
* **Chinese**: upstream's current voice uses a pinyin lexicon front end that
  exists only in JavaScript and Python.
* **Nepali, Hindi**: upstream's README describes them as browser-only. The
  same host check passes for both with this code, so adding them is a data
  and table change; it is left as a decision for the maintainers.
* **heart, heart-nano** (English, 24 kHz): a different runtime with a
  different phoneme front end; not wired up.

## Build

Requirements: JDK 17, Android SDK with `platforms;android-34`,
`build-tools;34.0.0` and NDK `27.2.12479018`.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

The Gradle build has not been run yet (see [Status](#status)). The APK
described below was produced by the equivalent manual pipeline:

```sh
ANDROID_HOME=/path/to/sdk tools/build-debug-apk.sh /path/to/outdir arm64-v8a
```

Host check (needs a C compiler and Python 3; Node.js and a sanoTTS checkout
only for the comparison against upstream):

```sh
node tests/wasm_reference.mjs /path/to/sanoTTS app/src/main/assets/voices /tmp/ref
python3 tests/host_test.py --reference /tmp/ref --jni     # --jni needs JAVA_HOME
```

## Install and select

```sh
adb install sanotts-debug.apk
```

The steps below are what the manifest is written to support. They have not
been tried on a device.

1. Settings > Accessibility > Text-to-speech output (on some devices:
   Settings > System > Languages & input > Text-to-speech output).
2. Preferred engine: choose **sanoTTS**.
3. Language: choose one of the languages above.
4. TalkBack speaks with the preferred engine.

The engine applies the system speech rate. It ignores the pitch setting: the
runtime has no pitch control.

## Status

What has been checked, on a Mac (arm64), 2026-11-18:

* **Host parity.** `tests/host_test.py` compiles the vendored C and
  `sanotts_android.c` natively and synthesises two sentences per voice. For
  all 12 voices: phoneme ids are identical to upstream's prebuilt WebAssembly
  modules, sample counts are identical, PCM correlation with the WebAssembly
  output is 1.000000, and the float16 voices widen to the sha256 recorded in
  their `meta.json`. Clips are 1.4 to 5.0 s, RMS 0.02 to 0.13.
* **JNI on a desktop JVM.** With `--jni`, the same test loads the app's own
  `Native` class and JNI entry points in a desktop JVM (OpenJDK 17) and gets
  PCM byte-identical to the C driver for all 12 voices. This exercises the JNI
  marshalling, not ART or bionic.
* **Native library.** `libsanotts_android.so` builds for `arm64-v8a` with NDK
  r27c through `jni/Android.mk`; the four JNI entry points are exported.
* **APK.** A debug APK (47.7 MB, `arm64-v8a` only) was assembled with
  `tools/build-debug-apk.sh` and passes `apksigner verify`. `aapt2 dump
  permissions` lists none. The Java compiles against the API 34 `android.jar`.

What has not been done:

* The APK has **not been installed or run** on any device or emulator. The JNI
  layer, the Java service, asset extraction and the manifest wiring are
  therefore untested at run time.
* It has **not been tested with TalkBack**, and **not with blind or low-vision
  users**.
* `./gradlew assembleDebug` has not been run. `armeabi-v7a` and `x86_64` have
  not been built, so the existing APK does not install on 32-bit-only phones,
  which many low-cost devices are.
* Whether the Settings "play sample" button works is unknown; the engine has
  no `GET_SAMPLE_TEXT` activity.
* Speed has not been measured on a phone. Latency matters more than quality
  for a screen reader; whether these 1.5 M-parameter float models are fast
  enough on low-end hardware is unknown. Upstream ships ~0.5 M-parameter
  variants of eight of the languages that could be substituted.
* A stop request takes effect between pieces of text (up to 120 characters),
  not inside one.
* A language change from a client waits for the utterance in progress, and a
  synthesis error in one piece abandons the rest of that utterance.
* No sample-text activity, no settings screen, no launcher icon.

## Licence

GPL-3.0-or-later; see [`LICENSE`](LICENSE). The copyleft comes from the
espeak-ng phonemizer, which is compiled into the native library. Upstream
releases part of its runtime under MIT, but the files used here are not among
them; see [`third_party/sanotts/README.md`](third_party/sanotts/README.md) for
the file-by-file provenance.

Copyright (C) 2026 Ampixa.
