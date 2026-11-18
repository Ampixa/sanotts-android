/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Ampixa
 */
#ifndef SANOTTS_ANDROID_H
#define SANOTTS_ANDROID_H

#include <stddef.h>
#include <stdint.h>

/* Error codes of this layer. -1..-13 are snt_voice_synthesize's own codes
 * (see third_party/sanotts/mcu/ports/wasm/snt_voice_wasm.c) and pass through. */
#define SA_ERR_ARGS (-100)
#define SA_ERR_NOMEM (-101)
#define SA_ERR_G2P_VOICE (-102)

typedef struct sa_voice sa_voice;

/* espeak_data_dir: a real directory holding phontab, the *_dict files and
 * lang/. Returns 0 on success. */
int sa_init(const char *espeak_data_dir);

/* Copies (and for float16 voices widens) the two weight blobs. The caller's
 * buffers are not retained. *_out_len, if not NULL, receive the sizes of the
 * prepared blobs. */
sa_voice *sa_voice_load(const uint8_t *front, size_t front_len,
                        const uint8_t *dec, size_t dec_len, int is_f16,
                        size_t *front_out_len, size_t *dec_out_len);
void sa_voice_free(sa_voice *v);
const uint8_t *sa_voice_front(const sa_voice *v);
const uint8_t *sa_voice_dec(const sa_voice *v);

/* Phoneme ids for text; returns the count or a negative error. */
int sa_text_to_ids(const char *espeak_voice, int g2p_slot, const char *text,
                   int32_t *ids, int max_ids);

/* UTF-8 text -> mono 22050 Hz PCM16. Returns the sample count (0 when the
 * text has nothing pronounceable, *pcm_out NULL) or a negative error.
 * The caller frees *pcm_out. */
int sa_synthesize(const sa_voice *v, const char *espeak_voice, int g2p_slot,
                  const char *text, float length_scale, int16_t **pcm_out);

#endif
