// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Ampixa
package com.ampixa.sanotts.tts;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.os.Build;
import android.os.SystemClock;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** System text-to-speech engine backed by the sanoTTS runtime and espeak-ng phonemizer. */
public class SanoTtsService extends TextToSpeechService {
    private static final String TAG = "SanoTTS";
    private static final int SAMPLE_RATE = 22050;

    /**
     * One voice per language. Columns: ISO 639-3 language, ISO 3166 alpha-3
     * country of the voice, directory under assets/voices (also the Android
     * voice name), BCP 47 tag of the voice, other countries whose users get
     * this voice (space separated, alpha-3), sample sentence.
     */
    static final String[][] VOICES = {
        {"eng", "USA", "amy", "en-US", "GBR IND AUS CAN IRL NZL ZAF SGP PHL NGA",
            "This is an example of speech synthesis in English."},
        {"deu", "DEU", "german", "de-DE", "AUT CHE",
            "Dies ist ein Beispiel für Sprachsynthese in Deutsch."},
        {"fra", "FRA", "french", "fr-FR", "CAN BEL CHE",
            "Voici un exemple de synthèse vocale en français."},
        {"spa", "ESP", "spanish", "es-ES", "MEX USA ARG COL CHL PER",
            "Este es un ejemplo de síntesis de voz en español."},
        {"ita", "ITA", "italian", "it-IT", "CHE",
            "Questo è un esempio di sintesi vocale in italiano."},
        {"por", "BRA", "portuguese", "pt-BR", "PRT",
            "Este é um exemplo de síntese de voz em português."},
        {"ron", "ROU", "romanian", "ro-RO", "MDA",
            "Acesta este un exemplu de sinteză vocală în limba română."},
        {"ces", "CZE", "czech", "cs-CZ", "",
            "Toto je příklad syntézy řeči v češtině."},
        {"tur", "TUR", "turkish", "tr-TR", "",
            "Bu, Türkçe konuşma sentezinin bir örneğidir."},
        {"pol", "POL", "polish", "pl-PL", "",
            "To jest przykład syntezy mowy w języku polskim."},
        {"vie", "VNM", "vietnamese", "vi-VN", "",
            "Đây là một ví dụ về tổng hợp giọng nói bằng tiếng Việt."},
        {"ind", "IDN", "indonesian", "id-ID", "",
            "Ini adalah contoh sintesis suara dalam bahasa Indonesia."},
    };

    private final Object lock = new Object();
    private volatile boolean stopRequested;

    // Guarded by lock. One voice is kept loaded at a time.
    private boolean nativeReady;
    private volatile String[] current; // written under lock; read without it by onGetLanguage
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

    static String[] findVoiceByName(String name) {
        for (String[] v : VOICES) {
            if (v[2].equals(name)) {
                return v;
            }
        }
        return null;
    }

    @Override
    public void onCreate() {
        long t0 = SystemClock.elapsedRealtime();
        Log.i(TAG, "onCreate: sdk=" + Build.VERSION.SDK_INT + " abi=" + Build.SUPPORTED_ABIS[0]
                + " locale=" + Locale.getDefault().toLanguageTag());
        super.onCreate(); // loads the default language through onLoadLanguage
        Log.i(TAG, "onCreate done in " + (SystemClock.elapsedRealtime() - t0) + " ms");
    }

    /**
     * Any country of a supported language gets that language's one voice.
     * LANG_COUNTRY_AVAILABLE is returned only for the voice's own country.
     */
    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        String[] v = findVoice(lang);
        int result = v == null ? TextToSpeech.LANG_NOT_SUPPORTED
                : v[1].equals(country) ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_AVAILABLE;
        Log.i(TAG, "onIsLanguageAvailable " + lang + "-" + country + " -> " + result);
        return result;
    }

    @Override
    protected String[] onGetLanguage() {
        String[] v = current;
        if (v == null) {
            v = VOICES[0];
        }
        return new String[] {v[0], v[1], ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        int available = onIsLanguageAvailable(lang, country, variant);
        if (available == TextToSpeech.LANG_NOT_SUPPORTED) {
            return available;
        }
        String[] v = findVoice(lang);
        if (!loadVoice(v)) {
            return TextToSpeech.LANG_MISSING_DATA;
        }
        Log.i(TAG, "onLoadLanguage " + lang + "-" + country + " -> " + available + " voice=" + v[2]);
        return available;
    }

    @Override
    public List<Voice> onGetVoices() {
        List<Voice> voices = new ArrayList<>();
        for (String[] v : VOICES) {
            voices.add(new Voice(v[2], Locale.forLanguageTag(v[3]), Voice.QUALITY_NORMAL,
                    Voice.LATENCY_NORMAL, false, new HashSet<String>()));
        }
        return voices;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        String[] v = findVoice(lang);
        Log.i(TAG, "onGetDefaultVoiceNameFor " + lang + "-" + country + " -> " + (v == null ? null : v[2]));
        return v == null ? null : v[2];
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
        return findVoiceByName(voiceName) != null ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String voiceName) {
        String[] v = findVoiceByName(voiceName);
        boolean ok = v != null && loadVoice(v);
        Log.i(TAG, "onLoadVoice " + voiceName + " -> " + (ok ? "ok" : "error"));
        return ok ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    private boolean loadVoice(String[] v) {
        synchronized (lock) {
            try {
                loadVoiceLocked(v);
                return true;
            } catch (IOException | JSONException e) {
                Log.e(TAG, "cannot load voice " + v[2], e);
                return false;
            }
        }
    }

    @Override
    protected void onStop() {
        stopRequested = true;
    }

    /** The text itself is never logged: it is whatever is on the user's screen. */
    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopRequested = false;
        long t0 = SystemClock.elapsedRealtime();
        String text = String.valueOf(request.getCharSequenceText());
        synchronized (lock) {
            // A client that chose a voice sends its name; older clients send only a language.
            String[] v = findVoiceByName(request.getVoiceName());
            if (v == null) {
                v = findVoice(request.getLanguage());
            }
            Log.i(TAG, "synthesize start: chars=" + text.length() + " lang=" + request.getLanguage() + "-"
                    + request.getCountry() + " voiceName=" + request.getVoiceName()
                    + " voice=" + (v == null ? null : v[2]) + " rate=" + request.getSpeechRate()
                    + " callerUid=" + request.getCallerUid());
            if (v == null) {
                Log.e(TAG, "synthesize error: unsupported language " + request.getLanguage());
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST);
                return;
            }
            try {
                loadVoiceLocked(v);
            } catch (IOException | JSONException e) {
                Log.e(TAG, "synthesize error: cannot load voice " + v[2], e);
                callback.error(TextToSpeech.ERROR_SYNTHESIS);
                return;
            }
            if (callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                Log.e(TAG, "synthesize error: callback.start failed");
                return;
            }
            // Android rate: 100 is normal, 200 twice as fast. The runtime takes a
            // duration multiplier. Pitch is not supported by the runtime and is ignored.
            float rate = Math.max(25, Math.min(400, request.getSpeechRate())) / 100f;
            float scale = lengthScale / rate;
            int maxBytes = callback.getMaxBufferSize() & ~1;
            int[] err = new int[1];
            int chunks = 0;
            long pcmBytes = 0;
            long synthMs = 0;
            long firstAudioMs = -1;
            for (String chunk : Chunker.split(text)) {
                if (stopRequested) {
                    break;
                }
                long c0 = SystemClock.elapsedRealtime();
                short[] pcm = Native.synthesize(handle, espeakVoice, g2pSlot,
                        chunk.getBytes(StandardCharsets.UTF_8), scale, err);
                synthMs += SystemClock.elapsedRealtime() - c0;
                if (pcm == null) {
                    Log.e(TAG, "synthesize error: native code " + err[0] + " for voice " + v[2]
                            + " chunkChars=" + chunk.length());
                    callback.error(TextToSpeech.ERROR_SYNTHESIS);
                    return;
                }
                if (firstAudioMs < 0) {
                    firstAudioMs = SystemClock.elapsedRealtime() - t0;
                }
                chunks++;
                byte[] bytes = new byte[pcm.length * 2];
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm);
                for (int off = 0; off < bytes.length && !stopRequested; off += maxBytes) {
                    int n = Math.min(maxBytes, bytes.length - off);
                    if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) {
                        // stopped or failed; the framework has the reason
                        Log.i(TAG, "synthesize ended by framework after pcmBytes=" + (pcmBytes + off));
                        return;
                    }
                }
                pcmBytes += bytes.length;
            }
            callback.done();
            long audioMs = pcmBytes * 1000 / (2 * SAMPLE_RATE);
            // wallMs includes time blocked in audioAvailable while audio plays.
            // A stop that arrives inside Native.synthesize only takes effect when that call
            // returns: the piece is computed in full and then thrown away.
            Log.i(TAG, (stopRequested ? "synthesize stopped: chars=" : "synthesize end: chars=") + text.length() + " voice=" + v[2] + " chunks=" + chunks
                    + " pcmBytes=" + pcmBytes + " audioMs=" + audioMs + " synthMs=" + synthMs
                    + " firstAudioMs=" + firstAudioMs + " wallMs=" + (SystemClock.elapsedRealtime() - t0));
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
            long t0 = SystemClock.elapsedRealtime();
            File espeak = extractEspeakData();
            long t1 = SystemClock.elapsedRealtime();
            int rc = Native.init(espeak.getAbsolutePath());
            Log.i(TAG, "native init: rc=" + rc + " dir=" + espeak + " extractMs=" + (t1 - t0)
                    + " initMs=" + (SystemClock.elapsedRealtime() - t1));
            if (rc != 0) {
                throw new IOException("espeak-ng initialisation failed with code " + rc);
            }
            nativeReady = true;
        }
        if (v == current) {
            return;
        }
        long t0 = SystemClock.elapsedRealtime();
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
        Log.i(TAG, "voice loaded: " + v[2] + " espeak=" + espeakVoice + " in "
                + (SystemClock.elapsedRealtime() - t0) + " ms");
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
            Log.i(TAG, "extracting espeak-ng data to " + dir);
            copyAssetTree(getAssets(), "espeak", dir);
            if (!marker.createNewFile()) {
                throw new IOException("cannot create " + marker);
            }
        }
        String[] files = dir.list();
        Log.i(TAG, "espeak-ng data ready: " + (files == null ? 0 : files.length) + " entries in " + dir);
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
