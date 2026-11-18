// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Ampixa
package com.ampixa.sanotts.tts;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Set;

/**
 * Answers android.speech.tts.engine.CHECK_TTS_DATA. Every voice ships inside the APK.
 *
 * The system settings screen only enables the engine's controls when the
 * user's locale, written as "lang-COUNTRY" in ISO 3 codes, is literally in
 * this list. So each language is listed alone, with the voice's own country,
 * with its other common countries, and with the countries of the device's
 * own locales; all of them are spoken by the language's one voice.
 */
public class CheckVoiceData extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ArrayList<String> available = new ArrayList<>(availableLocales());
        Log.i("SanoTTS", "CheckVoiceData: reporting " + available.size() + " locales");
        Intent result = new Intent();
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available);
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, new ArrayList<String>());
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result);
        finish();
    }

    static Set<String> availableLocales() {
        Set<String> out = new LinkedHashSet<>();
        for (String[] v : SanoTtsService.VOICES) {
            out.add(v[0]);
            out.add(v[0] + "-" + v[1]);
            for (String country : v[4].split(" ")) {
                if (!country.isEmpty()) {
                    out.add(v[0] + "-" + country);
                }
            }
        }
        for (Locale locale : deviceLocales()) {
            try {
                String lang = locale.getISO3Language();
                String country = locale.getISO3Country(); // "" for regions such as 419
                if (SanoTtsService.findVoice(lang) != null && !country.isEmpty()) {
                    out.add(lang + "-" + country);
                }
            } catch (MissingResourceException e) {
                Log.w("SanoTTS", "CheckVoiceData: no ISO 3 code for " + locale.toLanguageTag(), e);
            }
        }
        return out;
    }

    private static Locale[] deviceLocales() {
        if (Build.VERSION.SDK_INT < 24) {
            return new Locale[] {Locale.getDefault()};
        }
        LocaleList list = LocaleList.getDefault();
        Locale[] out = new Locale[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
