// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Ampixa
package com.ampixa.sanotts.tts;

/** JNI entry points of jni/sanotts_android.c. Not thread-safe; callers serialise. */
final class Native {
    static {
        System.loadLibrary("sanotts_android");
    }

    private Native() {}

    /** @return 0 on success. */
    static native int init(String espeakDataDir);

    /** @return a handle, or 0 if the blobs are malformed or memory ran out. */
    static native long loadVoice(byte[] front, byte[] dec, boolean float16);

    static native void freeVoice(long handle);

    /**
     * @param textUtf8 standard UTF-8, not JNI modified UTF-8
     * @param err receives the error code in err[0] when the result is null
     * @return mono 22050 Hz PCM16; empty when the text has nothing pronounceable
     */
    static native short[] synthesize(long handle, String espeakVoice, int g2pSlot,
                                     byte[] textUtf8, float lengthScale, int[] err);
}
