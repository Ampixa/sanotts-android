#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Ampixa
"""Host check of the C that the Android library is built from.

For every bundled voice: synthesises the sentences in
sentences.json, and asserts

  * float16 voices widen to the sha256 recorded in the voice's meta.json,
  * the audio is finite-length, longer than one second and not silent,
  * with --reference DIR (output of wasm_reference.mjs): phoneme ids are
    identical to upstream's WebAssembly build and the PCM matches it,
  * with --jni: the app's Native class, loaded in a desktop JVM, returns PCM
    byte-identical to the C driver's.

    python3 tests/host_test.py [--reference DIR] [--jni] [--espeak DIR]
                               [--voices DIR] [--keep-wav DIR]

Exit status 0 only if every voice passes.
"""
import argparse
import array
import hashlib
import json
import math
import os
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TP = ROOT / "third_party" / "sanotts" / "mcu"
ESPEAK = TP / "ports" / "wasm" / "espeak"
JNI = ROOT / "app" / "src" / "main" / "jni"
TESTS = ROOT / "tests"
SAMPLE_RATE = 22050

# Same list as upstream mcu/ports/wasm/build_g2p.sh and jni/Android.mk.
LIBESPEAK = """common.c mnemonics.c error.c ieee80.c compiledata.c compiledict.c
dictionary.c encoding.c intonation.c langopts.c numbers.c phoneme.c
phonemelist.c readclause.c setlengths.c soundicon.c spect.c ssml.c synthdata.c
synthesize.c tr_languages.c translate.c translateword.c voices.c wavegen.c
speech.c espeak_api.c""".split()
UCD = "case.c categories.c ctype.c proplist.c scripts.c tostring.c".split()


def build(out: Path, extra: list) -> None:
    """Compile the library sources plus `extra` (sources and flags) into `out`."""
    srcs = [str(ESPEAK / "libespeak-ng" / f) for f in LIBESPEAK]
    srcs += [str(ESPEAK / "ucd-tools" / f) for f in UCD]
    srcs += [
        str(TP / "src" / "snt_front_f32.c"),
        str(TP / "src" / "snt_piperlite.c"),
        str(TP / "ports" / "wasm" / "snt_voice_wasm.c"),
        str(JNI / "sanotts_android.c"),
    ]
    cmd = [
        "cc", "-O2", "-std=gnu11", "-w", "-DHAVE_CONFIG_H",
        f"-I{ESPEAK}", f"-I{ESPEAK / 'include'}", f"-I{ESPEAK / 'ucd-tools' / 'include'}",
        f"-I{ESPEAK / 'libespeak-ng'}", f"-I{TP / 'include'}", f"-I{TP / 'ports' / 'wasm'}",
        f"-I{JNI}", *srcs, *extra, "-lm", "-o", str(out),
    ]
    if sys.platform == "darwin":
        cmd.insert(1, f"-I{TESTS / 'darwin_compat'}")
    if subprocess.run(cmd).returncode != 0:
        sys.exit("host build failed (compiler output above)")


def build_jni(tmp: Path) -> list:
    """Build the JNI library and the app's Native class for a desktop JVM.

    Returns the java command prefix that runs tests/JniCheck.java."""
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        sys.exit("--jni needs JAVA_HOME")
    inc = Path(java_home) / "include"
    plat = "darwin" if sys.platform == "darwin" else "linux"
    lib = tmp / ("libsanotts_android.dylib" if sys.platform == "darwin" else "libsanotts_android.so")
    build(lib, ["-shared", "-fPIC", "-DSA_JNI", f"-I{inc}", f"-I{inc / plat}"])
    app = ROOT / "app/src/main/java/com/ampixa/sanotts/tts"
    classes = tmp / "classes"
    cmd = [str(Path(java_home) / "bin" / "javac"), "-d", str(classes),
           str(app / "Native.java"), str(TESTS / "JniCheck.java")]
    if subprocess.run(cmd).returncode != 0:
        sys.exit("javac failed (output above)")
    return [str(Path(java_home) / "bin" / "java"), f"-Djava.library.path={tmp}",
            "-cp", str(classes), "com.ampixa.sanotts.tts.JniCheck"]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def correlation(a, b) -> float:
    n = len(a)
    ma, mb = sum(a) / n, sum(b) / n
    num = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    den = math.sqrt(sum((x - ma) ** 2 for x in a) * sum((y - mb) ** 2 for y in b))
    return num / den if den else 0.0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path)
    ap.add_argument("--espeak", type=Path, default=ROOT / "app/src/main/assets/espeak")
    ap.add_argument("--voices", type=Path, default=ROOT / "app/src/main/assets/voices")
    ap.add_argument("--keep-wav", type=Path)
    ap.add_argument("--jni", action="store_true",
                    help="also run the app's JNI entry points on a desktop JVM (needs JAVA_HOME)")
    args = ap.parse_args()

    sentences = json.loads((ROOT / "tests" / "sentences.json").read_text(encoding="utf-8"))
    failed = []
    with tempfile.TemporaryDirectory() as tmp_name:
        tmp = Path(tmp_name)
        exe = tmp / "host_main"
        build(exe, [str(TESTS / "host_main.c")])
        jni_cmd = build_jni(tmp) if args.jni else None
        for vdir in sorted(p for p in args.voices.iterdir() if p.is_dir()):
            key = vdir.name
            if key not in sentences:
                print(f"FAIL {key}: no entry in sentences.json")
                failed.append(key)
                continue
            meta = json.loads((vdir / "meta.json").read_text(encoding="utf-8"))
            f16 = meta.get("weights") == "f16"
            prefix = tmp / key
            voice_args = [str(args.espeak), str(vdir), meta.get("front", "front_f32.bin"),
                          meta.get("dec", "dec_f32.bin"), "1" if f16 else "0", meta["espeak_voice"],
                          str(meta["g2p_voice_slot"]), str(meta["length_scale"])]
            proc = subprocess.run([str(exe), *voice_args, str(prefix), *sentences[key]],
                                  capture_output=True, text=True)
            problems = []
            if jni_cmd:
                jproc = subprocess.run([*jni_cmd, *voice_args, f"{prefix}.jni", *sentences[key]],
                                       capture_output=True, text=True)
                if jproc.returncode != 0:
                    problems.append(f"JniCheck exit {jproc.returncode}: {jproc.stderr.strip()[-300:]}")
                else:
                    for i in range(len(sentences[key])):
                        if Path(f"{prefix}.jni.{i}.pcm").read_bytes() != Path(f"{prefix}.{i}.pcm").read_bytes():
                            problems.append(f"sentence {i}: JNI PCM differs from the C driver")
            if proc.returncode != 0:
                problems.append(f"host_main exit {proc.returncode}: {proc.stderr.strip()}")
            else:
                if f16:
                    for part in ("front", "dec"):
                        got = sha256(Path(f"{prefix}.{part}.bin"))
                        if got != meta[f"{part}_widened_sha256"]:
                            problems.append(f"{part} widened sha256 mismatch")
                notes = []
                for i, text in enumerate(sentences[key]):
                    pcm = array.array("h")
                    pcm.frombytes(Path(f"{prefix}.{i}.pcm").read_bytes())
                    ids = Path(f"{prefix}.{i}.ids").read_text().split()
                    secs = len(pcm) / SAMPLE_RATE
                    rms = math.sqrt(sum(s * s for s in pcm) / max(len(pcm), 1)) / 32768
                    if secs < 1.0:
                        problems.append(f"sentence {i}: only {secs:.2f} s")
                    if rms < 0.01:
                        problems.append(f"sentence {i}: rms {rms:.4f} (silent)")
                    note = f"{len(ids)} ids {secs:.2f}s rms {rms:.3f}"
                    if args.reference:
                        ref_ids = (args.reference / f"{key}.{i}.ids").read_text().split()
                        if ids != ref_ids:
                            problems.append(f"sentence {i}: ids differ from wasm reference")
                        ref = array.array("f")
                        ref.frombytes((args.reference / f"{key}.{i}.f32").read_bytes())
                        if len(ref) != len(pcm):
                            problems.append(f"sentence {i}: {len(pcm)} samples, reference {len(ref)}")
                        else:
                            corr = correlation(pcm, ref)
                            note += f" corr {corr:.6f}"
                            if corr < 0.999:
                                problems.append(f"sentence {i}: correlation {corr:.6f} with reference")
                    notes.append(note)
                    if args.keep_wav:
                        args.keep_wav.mkdir(parents=True, exist_ok=True)
                        with wave.open(str(args.keep_wav / f"{key}.{i}.wav"), "wb") as w:
                            w.setnchannels(1)
                            w.setsampwidth(2)
                            w.setframerate(SAMPLE_RATE)
                            w.writeframes(pcm.tobytes())
            if problems:
                failed.append(key)
                print(f"FAIL {key}: " + "; ".join(problems))
            else:
                print(f"PASS {key} ({meta['espeak_voice']}, slot {meta['g2p_voice_slot']}, "
                      f"{'f16' if f16 else 'f32'}{', jni identical' if jni_cmd else ''}): "
                      + " | ".join(notes))
    if failed:
        print("FAILED: " + ", ".join(failed))
        return 1
    print("all voices passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
