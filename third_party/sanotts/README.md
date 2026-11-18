# Vendored from sanoTTS

Source: https://github.com/Ampixa/sanoTTS at commit
`8ba78d4f9bbd164cc0d3ddc36beba2c5a0c5530e` (2026-11-15). Files are unmodified
copies at their upstream paths; update by copying again from a newer commit
and re-running `tests/host_test.py`.

| Path here | Role | Licence |
| --- | --- | --- |
| `mcu/src/snt_front_f32.c`, `mcu/include/snt_front_f32.h` | duration + acoustic model (phoneme ids to latent) | GPL-3.0-or-later |
| `mcu/src/snt_piperlite.c`, `mcu/include/snt_piperlite.h` | decoder (latent to audio) | GPL-3.0-or-later |
| `mcu/ports/wasm/snt_voice_wasm.c` | chains the two above | GPL-3.0-or-later |
| `mcu/ports/wasm/snt_g2p_wasm.c`, `cp_id_tables_multi.h` | text to phoneme ids through espeak-ng | GPL-3.0-or-later |
| `mcu/ports/wasm/espeak/` | espeak-ng 1.52.0 translator sources and ucd-tools, as upstream ships them (includes upstream's `speech.c` data-path patch) | GPL-3.0-or-later |
| `LICENSE`, `LICENSE.MIT` | upstream licence texts | |

Upstream's `LICENSE.MIT` lists the files it releases under MIT. None of the
files vendored here are on that list (it covers the `snt_nano`/`snt_tts`
runtime and the `mobile/` bindings, which this app does not use), so all of
the above is GPL-3.0-or-later.

Also taken from the same commit, stored under `app/src/main/assets/`:

* `espeak/` from `mcu/ports/wasm/espeak-data-multi/`: `phontab`, `phonindex`,
  `phondata`, `intonations`, the dictionaries and `lang/` voice files for the
  languages this app ships. espeak-ng data, GPL-3.0-or-later.
* `voices/<key>/` from `web/voices/<key>/`: `meta.json` and the two weight
  blobs for each shipped voice. Distilled by Ampixa from MIT-licensed piper
  teacher voices; see upstream's README and `LICENSE.MIT` for the statement on
  weights.
