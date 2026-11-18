// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Ampixa
package com.ampixa.sanotts.tts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;
import java.util.MissingResourceException;

/**
 * Answers android.speech.tts.engine.GET_SAMPLE_TEXT: the sentence the system
 * settings screen speaks when the user presses Play.
 */
public class GetSampleText extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String language = getIntent().getStringExtra("language");
        String[] v = findVoice(language);
        Log.i("SanoTTS", "GetSampleText: language=" + language + " -> " + (v == null ? null : v[2]));
        Intent result = new Intent();
        if (v == null) {
            setResult(TextToSpeech.LANG_NOT_SUPPORTED, result);
        } else {
            result.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, v[5]);
            setResult(TextToSpeech.LANG_AVAILABLE, result);
        }
        finish();
    }

    /** Callers send either a two-letter or a three-letter language code. */
    private static String[] findVoice(String language) {
        if (language == null) {
            return null;
        }
        String[] v = SanoTtsService.findVoice(language);
        if (v != null) {
            return v;
        }
        try {
            return SanoTtsService.findVoice(new Locale(language).getISO3Language());
        } catch (MissingResourceException e) {
            Log.w("SanoTTS", "GetSampleText: unknown language code " + language, e);
            return null;
        }
    }
}
