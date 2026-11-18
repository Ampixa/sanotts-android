// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Ampixa
package com.ampixa.sanotts.tts;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Drives the app's own Native class on a desktop JVM, with the same arguments
 * as tests/host_main.c, so host_test.py can require byte-identical PCM from
 * the JNI path. Lives in the app's package to reach the package-private class.
 *
 *   java -Djava.library.path=DIR -cp CLASSES com.ampixa.sanotts.tts.JniCheck
 *        espeak_dir voice_dir front dec f16:0|1 espeak_voice slot length_scale
 *        out_prefix text...
 */
public final class JniCheck {
    public static void main(String[] a) throws IOException {
        int rc = Native.init(a[0]);
        if (rc != 0) {
            throw new IllegalStateException("Native.init returned " + rc);
        }
        long h = Native.loadVoice(Files.readAllBytes(Paths.get(a[1], a[2])),
                Files.readAllBytes(Paths.get(a[1], a[3])), a[4].equals("1"));
        if (h == 0) {
            throw new IllegalStateException("Native.loadVoice failed");
        }
        int[] err = new int[1];
        for (int i = 9; i < a.length; i++) {
            short[] pcm = Native.synthesize(h, a[5], Integer.parseInt(a[6]),
                    a[i].getBytes(StandardCharsets.UTF_8), Float.parseFloat(a[7]), err);
            if (pcm == null) {
                throw new IllegalStateException("Native.synthesize failed with code " + err[0]);
            }
            ByteBuffer out = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            out.asShortBuffer().put(pcm);
            Files.write(Paths.get(a[8] + "." + (i - 9) + ".pcm"), out.array());
        }
        Native.freeVoice(h);
    }
}
