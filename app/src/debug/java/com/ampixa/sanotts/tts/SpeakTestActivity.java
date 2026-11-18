// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Ampixa
package com.ampixa.sanotts.tts;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/**
 * Debug builds only. Speaks one sentence through this engine by its package
 * name, without touching the system's default engine, and writes the same
 * sentence to a WAV file with synthesizeToFile:
 *
 *   adb shell am start -n com.ampixa.sanotts.tts/.SpeakTestActivity --es lang es-MX
 *   adb pull /sdcard/Android/data/com.ampixa.sanotts.tts/files/es-MX.wav
 *
 * Extras: lang (BCP 47 tag, default en-US), text (default: the engine's
 * sample sentence for that language). Progress goes to logcat tag SanoTtsTest.
 */
public class SpeakTestActivity extends Activity {
    private static final String TAG = "SanoTtsTest";
    private TextToSpeech tts;
    private TextView view;
    private long t0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new TextView(this);
        view.setTextSize(16);
        view.setPadding(48, 48, 48, 48);
        setContentView(view);

        String tag = getIntent().getStringExtra("lang");
        final Locale locale = Locale.forLanguageTag(tag == null ? "en-US" : tag);
        String[] voice = SanoTtsService.findVoice(locale.getISO3Language());
        String extra = getIntent().getStringExtra("text");
        final String text = extra != null ? extra : voice != null ? voice[5] : "No voice for this language.";
        t0 = SystemClock.elapsedRealtime();
        say("sanoTTS engine test, locale " + locale.toLanguageTag());
        say("text: " + text);

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                say("onInit status=" + status + " (0 is success)");
                if (status != TextToSpeech.SUCCESS) {
                    return;
                }
                say("setLanguage(" + locale.toLanguageTag() + ") -> " + tts.setLanguage(locale)
                        + ", voice=" + (tts.getVoice() == null ? null : tts.getVoice().getName()));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        say("onStart " + id);
                    }

                    @Override
                    public void onDone(String id) {
                        say("onDone " + id);
                    }

                    @Override
                    public void onError(String id) {
                        say("onError " + id);
                    }

                    @Override
                    public void onError(String id, int code) {
                        say("onError " + id + " code=" + code);
                    }
                });
                String name = locale.toLanguageTag();
                File wav = new File(getExternalFilesDir(null), name + ".wav");
                say("synthesizeToFile " + wav + " -> "
                        + tts.synthesizeToFile(text, null, wav, "file-" + name));
                say("speak -> " + tts.speak(text, TextToSpeech.QUEUE_ADD, null, "speak-" + name));
            }
        }, getPackageName());
    }

    private void say(final String line) {
        final String stamped = "+" + (SystemClock.elapsedRealtime() - t0) + " ms  " + line;
        Log.i(TAG, stamped);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.append(stamped + "\n");
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
