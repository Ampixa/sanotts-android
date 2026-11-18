/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Ampixa
 *
 * Host driver for the same C the Android library is built from.
 *
 *   host_main <espeak_dir> <voice_dir> <front> <dec> <f16:0|1> <espeak_voice>
 *             <slot> <length_scale> <out_prefix> <text>...
 *
 * Writes <out_prefix>.front.bin / .dec.bin (the blobs as handed to the
 * runtime, for the sha256 check) and, per text i, <out_prefix>.<i>.ids
 * (space-separated) and <out_prefix>.<i>.pcm (s16le mono 22050 Hz).
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sanotts_android.h"

static uint8_t *slurp(const char *dir, const char *name, size_t *len) {
    char path[1024];
    FILE *f;
    long n;
    uint8_t *buf;
    snprintf(path, sizeof path, "%s/%s", dir, name);
    f = fopen(path, "rb");
    if (!f) { perror(path); exit(2); }
    fseek(f, 0, SEEK_END);
    n = ftell(f);
    fseek(f, 0, SEEK_SET);
    buf = (uint8_t *)malloc((size_t)n);
    if (!buf || fread(buf, 1, (size_t)n, f) != (size_t)n) { perror(path); exit(2); }
    fclose(f);
    *len = (size_t)n;
    return buf;
}

static void dump(const char *prefix, const char *suffix, const void *data, size_t len) {
    char path[1024];
    FILE *f;
    snprintf(path, sizeof path, "%s.%s", prefix, suffix);
    f = fopen(path, "wb");
    if (!f || fwrite(data, 1, len, f) != len) { perror(path); exit(2); }
    fclose(f);
}

int main(int argc, char **argv) {
    size_t fl, dl, pfl, pdl;
    uint8_t *front, *dec;
    sa_voice *v;
    int i, rc;

    if (argc < 11) { fprintf(stderr, "usage: see file header\n"); return 2; }
    rc = sa_init(argv[1]);
    if (rc != 0) { fprintf(stderr, "sa_init failed: %d\n", rc); return 1; }
    front = slurp(argv[2], argv[3], &fl);
    dec = slurp(argv[2], argv[4], &dl);
    v = sa_voice_load(front, fl, dec, dl, atoi(argv[5]), &pfl, &pdl);
    if (!v) { fprintf(stderr, "sa_voice_load failed\n"); return 1; }
    free(front);
    free(dec);
    dump(argv[9], "front.bin", sa_voice_front(v), pfl);
    dump(argv[9], "dec.bin", sa_voice_dec(v), pdl);

    for (i = 10; i < argc; i++) {
        int32_t ids[2048];
        int16_t *pcm = NULL;
        char suffix[32], line[2048 * 5];
        int n_ids, n, k, off = 0;

        n_ids = sa_text_to_ids(argv[6], atoi(argv[7]), argv[i], ids, 2048);
        if (n_ids < 0) { fprintf(stderr, "sa_text_to_ids failed: %d\n", n_ids); return 1; }
        for (k = 0; k < n_ids; k++) off += snprintf(line + off, sizeof line - (size_t)off, "%d ", ids[k]);
        snprintf(suffix, sizeof suffix, "%d.ids", i - 10);
        dump(argv[9], suffix, line, (size_t)off);

        n = sa_synthesize(v, argv[6], atoi(argv[7]), argv[i], (float)atof(argv[8]), &pcm);
        if (n < 0) { fprintf(stderr, "sa_synthesize failed: %d\n", n); return 1; }
        snprintf(suffix, sizeof suffix, "%d.pcm", i - 10);
        dump(argv[9], suffix, pcm, (size_t)n * 2);
        free(pcm);
    }
    sa_voice_free(v);
    return 0;
}
