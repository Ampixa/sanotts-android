// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Ampixa
package com.ampixa.sanotts.tts;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.os.Build;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** System text-to-speech engine backed by the sanoTTS runtime and espeak-ng phonemizer. */
public class SanoTtsService extends TextToSpeechService {
    private static final String TAG = "SanoTTS";
    private static final int SAMPLE_RATE = 22050;

    /** ISO 639-3 language, ISO 3166 alpha-3 country, directory under assets/voices. */
    static final String[][] VOICES = {
        {"eng", "USA", "amy"},
        {"deu", "DEU", "german"},
        {"fra", "FRA", "french"},
        {"spa", "ESP", "spanish"},
        {"ita", "ITA", "italian"},
        {"por", "BRA", "portuguese"},
        {"ron", "ROU", "romanian"},
        {"ces", "CZE", "czech"},
        {"tur", "TUR", "turkish"},
        {"pol", "POL", "polish"},
        {"vie", "VNM", "vietnamese"},
        {"ind", "IDN", "indonesian"},
    };

    private final Object lock = new Object();
    private volatile boolean stopRequested;

    // Guarded by lock. One voice is kept loaded at a time.
    private boolean nativeReady;
    private String[] current;
    private long handle;
    private String espeakVoice;
    private int g2pSlot;
    private float lengthScale;

    static String[] findVoice(String lang) {
        for (String[] v : VOICES) {
            if (v[0].equals(lang)) {
                return v;
            }
        }
        return null;
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        String[] v = findVoice(lang);
        if (v == null) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }
        return v[1].equals(country) ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        synchronized (lock) {
            String[] v = current != null ? current : VOICES[0];
            return new String[] {v[0], v[1], ""};
        }
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        int available = onIsLanguageAvailable(lang, country, variant);
        if (available == TextToSpeech.LANG_NOT_SUPPORTED) {
            return available;
        }
        synchronized (lock) {
            try {
                loadVoiceLocked(findVoice(lang));
            } catch (IOException | JSONException e) {
                Log.e(TAG, "cannot load voice for " + lang, e);
                return TextToSpeech.LANG_MISSING_DATA;
            }
        }
        return available;
    }

    @Override
    protected void onStop() {
        stopRequested = true;
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopRequested = false;
        synchronized (lock) {
            String[] v = findVoice(request.getLanguage());
            if (v == null) {
                Log.e(TAG, "unsupported language " + request.getLanguage());
                callback.error();
                return;
            }
            try {
                loadVoiceLocked(v);
            } catch (IOException | JSONException e) {
                Log.e(TAG, "cannot load voice " + v[2], e);
                callback.error();
                return;
            }
            if (callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                return;
            }
            // Android rate: 100 is normal, 200 twice as fast. The runtime takes a
            // duration multiplier. Pitch is not supported by the runtime and is ignored.
            float rate = Math.max(25, Math.min(400, request.getSpeechRate())) / 100f;
            float scale = lengthScale / rate;
            int maxBytes = callback.getMaxBufferSize() & ~1;
            int[] err = new int[1];
            for (String chunk : Chunker.split(String.valueOf(request.getCharSequenceText()))) {
                if (stopRequested) {
                    break;
                }
                short[] pcm = Native.synthesize(handle, espeakVoice, g2pSlot,
                        chunk.getBytes(StandardCharsets.UTF_8), scale, err);
                if (pcm == null) {
                    Log.e(TAG, "synthesis failed with code " + err[0] + " for voice " + v[2]);
                    callback.error();
                    return;
                }
                byte[] bytes = new byte[pcm.length * 2];
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm);
                for (int off = 0; off < bytes.length && !stopRequested; off += maxBytes) {
                    int n = Math.min(maxBytes, bytes.length - off);
                    if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) {
                        return; // stopped or failed; the framework has the reason
                    }
                }
            }
            callback.done();
        }
    }

    @Override
    public void onDestroy() {
        synchronized (lock) {
            if (handle != 0) {
                Native.freeVoice(handle);
                handle = 0;
                current = null;
            }
        }
        super.onDestroy();
    }

    private void loadVoiceLocked(String[] v) throws IOException, JSONException {
        if (!nativeReady) {
            File espeak = extractEspeakData();
            int rc = Native.init(espeak.getAbsolutePath());
            if (rc != 0) {
                throw new IOException("espeak-ng initialisation failed with code " + rc);
            }
            nativeReady = true;
        }
        if (v == current) {
            return;
        }
        AssetManager assets = getAssets();
        String dir = "voices/" + v[2] + "/";
        JSONObject meta = new JSONObject(new String(readAsset(assets, dir + "meta.json"), StandardCharsets.UTF_8));
        long h = Native.loadVoice(
                readAsset(assets, dir + meta.optString("front", "front_f32.bin")),
                readAsset(assets, dir + meta.optString("dec", "dec_f32.bin")),
                "f16".equals(meta.optString("weights")));
        if (h == 0) {
            throw new IOException("voice " + v[2] + " is malformed or memory ran out");
        }
        if (handle != 0) {
            Native.freeVoice(handle);
        }
        handle = h;
        espeakVoice = meta.getString("espeak_voice");
        g2pSlot = meta.getInt("g2p_voice_slot");
        lengthScale = (float) meta.getDouble("length_scale");
        current = v;
    }

    /**
     * espeak-ng reads its data with fopen(), so the files must exist outside the
     * APK. Copied once per installed build, into device-protected storage where
     * available so the engine can speak on the lock screen before first unlock.
     */
    private File extractEspeakData() throws IOException {
        Context storage = Build.VERSION.SDK_INT >= 24 ? createDeviceProtectedStorageContext() : this;
        File dir = new File(storage.getFilesDir(), "espeak");
        long installed;
        try {
            installed = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new IOException("own package not found", e);
        }
        File marker = new File(dir, "extracted-" + installed);
        if (!marker.exists()) {
            copyAssetTree(getAssets(), "espeak", dir);
            if (!marker.createNewFile()) {
                throw new IOException("cannot create " + marker);
            }
        }
        return dir;
    }

    private static void copyAssetTree(AssetManager assets, String path, File target) throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) { // a file
            try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
            }
            return;
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("cannot create " + target);
        }
        for (String child : children) {
            copyAssetTree(assets, path + "/" + child, new File(target, child));
        }
    }

    private static byte[] readAsset(AssetManager assets, String path) throws IOException {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(in.available(), 1024));
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
