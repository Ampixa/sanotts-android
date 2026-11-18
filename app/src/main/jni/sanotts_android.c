/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Ampixa
 *
 * sanotts_android.c -- text -> PCM16 for the Android TTS service.
 *
 * Glue only. The phonemizer shim (snt_g2p_wasm.c) and the synth chain
 * (snt_voice_wasm.c) are the upstream sanoTTS files, compiled unmodified;
 * this file adds a configurable espeak-ng data path, float16 weight widening
 * and the JNI entry points.
 *
 * Not thread-safe: espeak-ng keeps global state. The Java side serialises.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "espeak-ng/espeak_ng.h"
#include "espeak-ng/speak_lib.h"

#include "sanotts_android.h"

/* Upstream hard-codes the Emscripten mount point "/espeak" in snt_g2p_init().
 * Including the shim here with the call redirected keeps that file byte-for-
 * byte upstream. The espeak headers are included above, before the macro, so
 * the real prototype is untouched. */
static char g_espeak_dir[512];
static void sa_initialize_path(const char *ignored) {
    (void)ignored;
    espeak_ng_InitializePath(g_espeak_dir);
}
#define espeak_ng_InitializePath sa_initialize_path
#include "snt_g2p_wasm.c"
#undef espeak_ng_InitializePath

int snt_voice_synthesize(const uint8_t *front_blob, const uint8_t *dec_blob,
                         const int32_t *ids, int n_ids, float length_scale,
                         float *out, int out_cap);

#define SA_SAMPLE_RATE 22050
#define SA_MAX_IDS 2048
#define SA_OUT_CAP (SA_SAMPLE_RATE * 60) /* one chunk; -11 from the runtime if exceeded */

struct sa_voice {
    uint8_t *front;
    uint8_t *dec;
};

int sa_init(const char *espeak_data_dir) {
    if (!espeak_data_dir || strlen(espeak_data_dir) >= sizeof(g_espeak_dir)) return -1;
    strcpy(g_espeak_dir, espeak_data_dir);
    return snt_g2p_init();
}

static int32_t sa_rd_i32(const uint8_t *p) {
    return (int32_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8) |
                     ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24));
}

static float sa_half_to_float(uint16_t h) {
    uint32_t sign = (uint32_t)(h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1fu;
    uint32_t frac = h & 0x3ffu;
    uint32_t bits;
    float f;
    if (exp == 0) {
        if (frac == 0) {
            bits = sign;
        } else { /* subnormal: normalise */
            int e = -1;
            do { e++; frac <<= 1; } while (!(frac & 0x400u));
            bits = sign | ((uint32_t)(127 - 15 - e) << 23) | ((frac & 0x3ffu) << 13);
        }
    } else if (exp == 31) {
        bits = sign | 0x7f800000u | (frac << 13);
    } else {
        bits = sign | ((exp + 127 - 15) << 23) | (frac << 13);
    }
    memcpy(&f, &bits, sizeof f);
    return f;
}

/* Returns a malloc'd blob the runtime can read: a copy for f32, or the header
 * followed by widened weights for f16. n_tensors_off/table_off are the header
 * layout documented in snt_voice_wasm.c (68/72 front, 44/48 decoder).
 * out_len receives the size of the returned blob. NULL on a malformed blob. */
static uint8_t *sa_prepare_blob(const uint8_t *src, size_t len, int is_f16,
                                int n_tensors_off, int table_off, size_t *out_len) {
    uint8_t *out;
    if (!src || len < (size_t)table_off) return NULL;
    if (!is_f16) {
        out = (uint8_t *)malloc(len);
        if (!out) return NULL;
        memcpy(out, src, len);
        if (out_len) *out_len = len;
        return out;
    }
    {
        int32_t n_tensors = sa_rd_i32(src + n_tensors_off);
        size_t head, n, i;
        if (n_tensors <= 0 || n_tensors > 96) return NULL;
        head = (size_t)table_off + 8u * (size_t)n_tensors;
        if (len < head || ((len - head) & 1u)) return NULL;
        n = (len - head) / 2;
        out = (uint8_t *)malloc(head + 4 * n);
        if (!out) return NULL;
        memcpy(out, src, head);
        for (i = 0; i < n; i++) {
            uint16_t h = (uint16_t)(src[head + 2 * i] | (src[head + 2 * i + 1] << 8));
            float f = sa_half_to_float(h);
            memcpy(out + head + 4 * i, &f, 4); /* Android ABIs and the host are little-endian */
        }
        if (out_len) *out_len = head + 4 * n;
        return out;
    }
}

void sa_voice_free(sa_voice *v) {
    if (!v) return;
    free(v->front);
    free(v->dec);
    free(v);
}

sa_voice *sa_voice_load(const uint8_t *front, size_t front_len,
                        const uint8_t *dec, size_t dec_len, int is_f16,
                        size_t *front_out_len, size_t *dec_out_len) {
    sa_voice *v = (sa_voice *)calloc(1, sizeof *v);
    if (!v) return NULL;
    v->front = sa_prepare_blob(front, front_len, is_f16, 68, 72, front_out_len);
    v->dec = sa_prepare_blob(dec, dec_len, is_f16, 44, 48, dec_out_len);
    if (!v->front || !v->dec) { sa_voice_free(v); return NULL; }
    return v;
}

const uint8_t *sa_voice_front(const sa_voice *v) { return v->front; }
const uint8_t *sa_voice_dec(const sa_voice *v) { return v->dec; }

int sa_text_to_ids(const char *espeak_voice, int g2p_slot, const char *text,
                   int32_t *ids, int max_ids) {
    if (snt_g2p_set_voice(espeak_voice, g2p_slot) != 0) return SA_ERR_G2P_VOICE;
    return snt_g2p_text_to_ids(text, ids, max_ids);
}

int sa_synthesize(const sa_voice *v, const char *espeak_voice, int g2p_slot,
                  const char *text, float length_scale, int16_t **pcm_out) {
    int32_t ids[SA_MAX_IDS];
    float *f;
    int16_t *pcm;
    int n_ids, n, i;

    *pcm_out = NULL;
    if (!v || !text) return SA_ERR_ARGS;
    n_ids = sa_text_to_ids(espeak_voice, g2p_slot, text, ids, SA_MAX_IDS);
    if (n_ids < 0) return n_ids;
    if (n_ids <= 3) return 0; /* BOS, PAD, EOS only: nothing pronounceable */

    f = (float *)malloc((size_t)SA_OUT_CAP * sizeof(float));
    if (!f) return SA_ERR_NOMEM;
    n = snt_voice_synthesize(v->front, v->dec, ids, n_ids, length_scale, f, SA_OUT_CAP);
    if (n <= 0) { free(f); return n < 0 ? n : 0; }

    pcm = (int16_t *)malloc((size_t)n * sizeof(int16_t));
    if (!pcm) { free(f); return SA_ERR_NOMEM; }
    for (i = 0; i < n; i++) {
        float s = f[i];
        if (!(s > -1.0f)) s = -1.0f; /* also catches NaN */
        if (s > 1.0f) s = 1.0f;
        pcm[i] = (int16_t)(s * 32767.0f);
    }
    free(f);
    *pcm_out = pcm;
    return n;
}

#ifdef __ANDROID__
#include <jni.h>

#define JNI_FN(name) Java_com_ampixa_sanotts_tts_Native_##name

JNIEXPORT jint JNICALL JNI_FN(init)(JNIEnv *env, jclass cls, jstring dir) {
    const char *d;
    jint rc;
    (void)cls;
    d = (*env)->GetStringUTFChars(env, dir, NULL);
    if (!d) return SA_ERR_NOMEM;
    rc = sa_init(d);
    (*env)->ReleaseStringUTFChars(env, dir, d);
    return rc;
}

JNIEXPORT jlong JNICALL JNI_FN(loadVoice)(JNIEnv *env, jclass cls, jbyteArray front,
                                          jbyteArray dec, jboolean f16) {
    jsize fl = (*env)->GetArrayLength(env, front);
    jsize dl = (*env)->GetArrayLength(env, dec);
    jbyte *f, *d;
    sa_voice *v = NULL;
    (void)cls;
    f = (*env)->GetByteArrayElements(env, front, NULL);
    d = (*env)->GetByteArrayElements(env, dec, NULL);
    if (f && d)
        v = sa_voice_load((const uint8_t *)f, (size_t)fl, (const uint8_t *)d, (size_t)dl,
                          f16 ? 1 : 0, NULL, NULL);
    if (f) (*env)->ReleaseByteArrayElements(env, front, f, JNI_ABORT);
    if (d) (*env)->ReleaseByteArrayElements(env, dec, d, JNI_ABORT);
    return (jlong)(intptr_t)v;
}

JNIEXPORT void JNICALL JNI_FN(freeVoice)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sa_voice_free((sa_voice *)(intptr_t)h);
}

/* text is standard UTF-8 bytes from Java (String.getBytes(UTF_8)), not JNI
 * "modified UTF-8", so characters outside the BMP reach espeak-ng intact.
 * Returns the PCM, an empty array when there is nothing to say, or NULL on
 * error with the code stored in err[0]. */
JNIEXPORT jshortArray JNICALL JNI_FN(synthesize)(JNIEnv *env, jclass cls, jlong h,
                                                 jstring espeak_voice, jint slot,
                                                 jbyteArray text, jfloat length_scale,
                                                 jintArray err) {
    const char *ev;
    jsize tl = (*env)->GetArrayLength(env, text);
    char *t = (char *)malloc((size_t)tl + 1);
    int16_t *pcm = NULL;
    jint n;
    jshortArray out = NULL;
    (void)cls;
    if (!t) { n = SA_ERR_NOMEM; goto fail; }
    (*env)->GetByteArrayRegion(env, text, 0, tl, (jbyte *)t);
    t[tl] = '\0';
    ev = (*env)->GetStringUTFChars(env, espeak_voice, NULL);
    if (!ev) { free(t); n = SA_ERR_NOMEM; goto fail; }
    n = sa_synthesize((const sa_voice *)(intptr_t)h, ev, slot, t, length_scale, &pcm);
    (*env)->ReleaseStringUTFChars(env, espeak_voice, ev);
    free(t);
    if (n < 0) goto fail;
    out = (*env)->NewShortArray(env, n);
    if (out && n > 0) (*env)->SetShortArrayRegion(env, out, 0, n, pcm);
    free(pcm);
    if (!out) { n = SA_ERR_NOMEM; goto fail; }
    return out;
fail:
    (*env)->SetIntArrayRegion(env, err, 0, 1, &n);
    return NULL;
}
#endif
