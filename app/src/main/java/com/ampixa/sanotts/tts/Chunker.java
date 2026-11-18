// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Ampixa
package com.ampixa.sanotts.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits text into pieces the runtime synthesises one at a time, so audio for
 * the first piece can play while the rest is computed and a stop request is
 * honoured between pieces. Same rules as splitChunks() in upstream
 * web/index.html: sentence ends first, then clause breaks for long sentences.
 */
final class Chunker {
    private static final int MAX = 120;
    private static final int MIN = 40;
    private static final Pattern SENTENCE =
            Pattern.compile("[^.!?।。！？]+[.!?।。！？]*\\s*");
    private static final Pattern CLAUSE = Pattern.compile("[,;:、，；：]\\s*");
    private static final Pattern ENDS_DIGIT_DOT = Pattern.compile(".*\\p{Nd}\\.\\s*", Pattern.DOTALL);
    private static final Pattern STARTS_DIGIT = Pattern.compile("\\s*\\p{Nd}.*", Pattern.DOTALL);

    private Chunker() {}

    static List<String> split(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher m = SENTENCE.matcher(text);
        while (m.find()) {
            sentences.add(m.group());
        }
        // "3.5" is a decimal point, not two sentences.
        for (int i = sentences.size() - 2; i >= 0; i--) {
            if (ENDS_DIGIT_DOT.matcher(sentences.get(i)).matches()
                    && STARTS_DIGIT.matcher(sentences.get(i + 1)).matches()) {
                sentences.set(i, sentences.get(i).trim() + sentences.remove(i + 1));
            }
        }
        List<String> chunks = new ArrayList<>();
        for (String sentence : sentences) {
            String s = sentence.trim();
            while (s.length() > MAX) {
                int cut = -1;
                Matcher c = CLAUSE.matcher(s);
                while (c.find()) {
                    if (c.end() >= MIN && c.end() <= MAX) {
                        cut = c.end();
                    }
                }
                if (cut < 0) {
                    int space = s.lastIndexOf(' ', MAX);
                    cut = space > MIN ? space + 1 : MAX;
                    // Do not cut a surrogate pair in half.
                    if (Character.isLowSurrogate(s.charAt(cut)) && Character.isHighSurrogate(s.charAt(cut - 1))) {
                        cut--;
                    }
                }
                chunks.add(s.substring(0, cut).trim());
                s = s.substring(cut).trim();
            }
            if (!s.isEmpty()) {
                chunks.add(s);
            }
        }
        return chunks;
    }

    /** Self-check on a desktop JVM: {@code java -cp <classes> com.ampixa.sanotts.tts.Chunker}. */
    public static void main(String[] args) {
        expect(split("Hello there. How are you?"), "Hello there.", "How are you?");
        expect(split("It costs 3.5 dollars. Fine."), "It costs 3.5 dollars.", "Fine.");
        expect(split("   "));
        expect(split("no terminator"), "no terminator");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longText.append("word").append(i).append(i % 7 == 6 ? ", " : " ");
        }
        StringBuilder joined = new StringBuilder();
        for (String chunk : split(longText.toString())) {
            if (chunk.isEmpty() || chunk.length() > MAX) {
                throw new AssertionError("bad chunk length " + chunk.length());
            }
            joined.append(chunk).append(' ');
        }
        if (!joined.toString().replace(" ", "").equals(longText.toString().replace(" ", ""))) {
            throw new AssertionError("chunks lost text");
        }
        System.out.println("Chunker self-check passed");
    }

    private static void expect(List<String> got, String... want) {
        if (!got.equals(java.util.Arrays.asList(want))) {
            throw new AssertionError("got " + got);
        }
    }
}
