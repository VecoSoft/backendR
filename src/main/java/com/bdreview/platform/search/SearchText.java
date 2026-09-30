package com.bdreview.platform.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Input sanitising + normalisation shared by the lexicon (so aliases and user input go through the
 * exact same transform) and the parser. Everything that reaches SQL is derived from these tokens, so
 * this is also the input-safety boundary: only letters (Latin + Bengali block), digits and single
 * spaces survive, and length is capped.
 */
final class SearchText {

    static final int MAX_QUERY_CHARS = 100;
    static final int MAX_TOKENS = 12;

    private SearchText() {
    }

    /**
     * NFC first: Bangla has two encodings for letters like য় (U+09DF vs U+09AF+U+09BC) depending on
     * the keyboard — NFC maps both to the same sequence, so "বিরিয়ানি" typed either way compares equal.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.length() > MAX_QUERY_CHARS * 2 ? raw.substring(0, MAX_QUERY_CHARS * 2) : raw;
        s = Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isBengali(cp) || Character.isLetterOrDigit(cp)) {
                out.appendCodePoint(cp);
            } else {
                // Punctuation, symbols, control chars, quotes, %, _ etc. all become a separator.
                out.append(' ');
            }
        }
        String collapsed = out.toString().replaceAll("\\s+", " ").trim();
        return collapsed.length() > MAX_QUERY_CHARS ? collapsed.substring(0, MAX_QUERY_CHARS).trim() : collapsed;
    }

    static List<String> tokens(String normalized) {
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>(Arrays.asList(normalized.split(" ")));
        return tokens.size() > MAX_TOKENS ? tokens.subList(0, MAX_TOKENS) : tokens;
    }

    /** Bengali block including its vowel signs/virama/nukta, which Character.isLetter reports inconsistently. */
    static boolean isBengali(int cp) {
        return cp >= 0x0980 && cp <= 0x09FF;
    }

    static boolean hasBengali(String s) {
        return s.codePoints().anyMatch(SearchText::isBengali);
    }

    /** Plain Levenshtein, bounded — callers only care whether it's within `max`. */
    static int editDistance(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) {
            return max + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) {
                return max + 1;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
