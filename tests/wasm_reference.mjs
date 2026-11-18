// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Ampixa
//
// Reference output from upstream's prebuilt WebAssembly modules, the build
// the browser demo runs. host_test.py compares the native build against it.
//
//   node wasm_reference.mjs <sanoTTS checkout> <voices dir> <out dir>
//
// For every voice directory in <voices dir> that has an entry in
// sentences.json, writes <out>/<key>.<i>.ids (space-separated) and
// <out>/<key>.<i>.f32 (float32 LE mono 22050 Hz).
import { readFileSync, readdirSync, writeFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const [upstream, voicesDir, outDir] = process.argv.slice(2);
if (!upstream || !voicesDir || !outDir) {
  console.error("usage: node wasm_reference.mjs <sanoTTS checkout> <voices dir> <out dir>");
  process.exit(2);
}
const here = dirname(fileURLToPath(import.meta.url));
const web = resolve(upstream, "web");
const sentences = JSON.parse(readFileSync(resolve(here, "sentences.json"), "utf8"));
const MAX_IDS = 2048, OUT_CAP = 22050 * 60;

const V = await (await import(resolve(web, "snt_voice.js"))).default();
const G = await (await import(resolve(web, "snt_g2p.js"))).default({
  locateFile: (f) => resolve(web, f), print: () => {}, printErr: () => {},
});
if (G._snt_g2p_init() !== 0) throw new Error("snt_g2p_init failed");

// Same widening as web/index.html widenF16.
function widenF16(bytes, nTensorsOff, tableOff) {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const head = tableOff + 8 * dv.getInt32(nTensorsOff, true);
  const n = (bytes.length - head) / 2;
  const out = new Uint8Array(head + 4 * n);
  out.set(bytes.subarray(0, head), 0);
  const dst = new DataView(out.buffer, head);
  for (let i = 0; i < n; i++) {
    const b = dv.getUint16(head + i * 2, true);
    const exp = (b >> 10) & 0x1f, frac = b & 0x3ff, sign = b & 0x8000 ? -1 : 1;
    const v = exp === 0 ? sign * frac * 5.960464477539063e-8
      : exp === 31 ? (frac ? NaN : sign * Infinity)
      : sign * Math.pow(2, exp - 25) * (1024 + frac);
    dst.setFloat32(i * 4, v, true);
  }
  return out;
}
function toHeap(M, bytes) {
  const p = M._malloc(bytes.length);
  M.HEAPU8.set(bytes, p);
  return p;
}

mkdirSync(outDir, { recursive: true });
for (const key of readdirSync(voicesDir).sort()) {
  if (!sentences[key]) continue;
  const meta = JSON.parse(readFileSync(resolve(voicesDir, key, "meta.json"), "utf8"));
  const f16 = meta.weights === "f16";
  let front = new Uint8Array(readFileSync(resolve(voicesDir, key, meta.front || "front_f32.bin")));
  let dec = new Uint8Array(readFileSync(resolve(voicesDir, key, meta.dec || "dec_f32.bin")));
  if (f16) { front = widenF16(front, 68, 72); dec = widenF16(dec, 44, 48); }
  const frontP = toHeap(V, front), decP = toHeap(V, dec);

  const voiceP = toHeap(G, new TextEncoder().encode(meta.espeak_voice + "\0"));
  if (G._snt_g2p_set_voice(voiceP, meta.g2p_voice_slot) !== 0) throw new Error(key + ": set_voice failed");
  G._free(voiceP);

  sentences[key].forEach((text, i) => {
    const textP = toHeap(G, new TextEncoder().encode(text + "\0"));
    const idsG = G._malloc(MAX_IDS * 4);
    const n = G._snt_g2p_text_to_ids(textP, idsG, MAX_IDS);
    if (n <= 0) throw new Error(key + ": text_to_ids returned " + n);
    const ids = Int32Array.from(G.HEAP32.subarray(idsG >> 2, (idsG >> 2) + n));
    G._free(textP); G._free(idsG);

    const idsP = toHeap(V, new Uint8Array(ids.buffer));
    const outP = V._malloc(OUT_CAP * 4);
    const ns = V._snt_voice_synthesize(frontP, decP, idsP, n, meta.length_scale, outP, OUT_CAP);
    if (ns <= 0) throw new Error(key + ": snt_voice_synthesize returned " + ns);
    const pcm = Float32Array.from(V.HEAPF32.subarray(outP >> 2, (outP >> 2) + ns));
    V._free(idsP); V._free(outP);

    writeFileSync(resolve(outDir, `${key}.${i}.ids`), Array.from(ids).join(" ") + " ");
    writeFileSync(resolve(outDir, `${key}.${i}.f32`), new Uint8Array(pcm.buffer));
  });
  V._free(frontP); V._free(decP);
  console.log(key, "reference written");
}
