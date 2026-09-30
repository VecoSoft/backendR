package com.bdreview.platform.search;

import com.bdreview.platform.search.SearchIntent.PricePreference;
import com.bdreview.platform.search.SearchLexicon.Entry;
import com.bdreview.platform.search.SearchLexicon.Signal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic query → intent extraction. No network, no model: a query is normalised, each word
 * is canonicalised (Bangla/Banglish suffix stripping, then bounded edit-distance typo correction),
 * and the words are scanned left to right matching the longest lexicon phrase first — so
 * "kom dami biriyani near mirpur" and "কম দামে বিরিয়ানি মিরপুর" both resolve to
 * {biryani, Mirpur, price LOW}. Runs in microseconds; safe to call per request.
 */
@Component
public class SearchIntentParser {

    private final SearchLexicon lexicon;
    private final SearchReferenceData referenceData;

    public SearchIntentParser(SearchLexicon lexicon, SearchReferenceData referenceData) {
        this.lexicon = lexicon;
        this.referenceData = referenceData;
    }

    public SearchIntent parse(String rawQuery) {
        String normalized = SearchText.normalize(rawQuery);
        List<String> original = SearchText.tokens(normalized);
        SearchReferenceData.Snapshot ref = referenceData.get();

        List<String> words = new ArrayList<>(original.size());
        boolean corrected = false;
        for (String w : original) {
            words.add(canonicalWord(w, ref));
        }

        List<SearchLexicon.Concept> concepts = new ArrayList<>();
        List<String> freeTerms = new ArrayList<>();
        String areaName = null;
        String cityName = null;
        PricePreference price = PricePreference.NONE;
        boolean ratingHigh = false;
        boolean nearMe = false;
        boolean openNow = false;

        int i = 0;
        while (i < words.size()) {
            Match m = longestMatch(words, i, ref);
            if (m == null) {
                String word = words.get(i);
                String fixed = typoCorrect(word);
                if (fixed != null) {
                    m = new Match(lookup(fixed, ref), 1);
                    words.set(i, fixed);
                    corrected = true;
                } else {
                    if (word.length() >= 2) {
                        freeTerms.add(word);
                    }
                    i++;
                    continue;
                }
            }
            Entry e = m.entry();
            switch (e.signal()) {
                case CONCEPT -> {
                    SearchLexicon.Concept c = lexicon.concept(e.value());
                    if (c != null && !concepts.contains(c)) {
                        concepts.add(c);
                    }
                }
                case AREA -> {
                    if (areaName == null) {
                        areaName = e.value();
                    }
                }
                case CITY -> {
                    if (cityName == null) {
                        cityName = e.value();
                    }
                }
                case PRICE_LOW -> price = PricePreference.LOW;
                case PRICE_HIGH -> price = PricePreference.HIGH;
                case RATING_HIGH -> ratingHigh = true;
                case NEAR_ME -> nearMe = true;
                case OPEN_NOW -> openNow = true;
                case STOP -> {
                }
            }
            i += m.length();
        }

        SearchReferenceData.AreaRef area = areaName == null ? null : ref.areasByLowerName().get(areaName.toLowerCase());
        SearchReferenceData.CityRef city = cityName == null ? null : ref.citiesByLowerName().get(cityName.toLowerCase());
        if (area != null) {
            areaName = area.name();
        }
        if (city != null) {
            cityName = city.name();
        }
        // "Mirpur, Dhaka" — the area already pins the city; keep the city only when it adds information.
        if (area != null && city != null && area.cityId().equals(city.id())) {
            city = null;
            cityName = null;
        }
        String correctedQuery = corrected ? String.join(" ", words) : null;
        return new SearchIntent(normalized, correctedQuery, List.copyOf(concepts), List.copyOf(freeTerms),
                areaName, area != null ? area.id() : null,
                cityName, city != null ? city.id() : null,
                price, ratingHigh, nearMe, openNow);
    }

    private record Match(Entry entry, int length) {
    }

    private Match longestMatch(List<String> words, int start, SearchReferenceData.Snapshot ref) {
        int max = Math.min(lexicon.maxPhraseWords(), words.size() - start);
        for (int n = max; n >= 1; n--) {
            String phrase = String.join(" ", words.subList(start, start + n));
            Entry e = lookup(phrase, ref);
            if (e != null) {
                return new Match(e, n);
            }
        }
        return null;
    }

    /** Lexicon first, then any area/city that exists in the database under its own name. */
    private Entry lookup(String phrase, SearchReferenceData.Snapshot ref) {
        Entry e = lexicon.lookup(phrase);
        if (e != null) {
            return e;
        }
        SearchReferenceData.AreaRef area = ref.areasByLowerName().get(phrase);
        if (area != null) {
            return new Entry(Signal.AREA, area.name());
        }
        SearchReferenceData.CityRef city = ref.citiesByLowerName().get(phrase);
        if (city != null) {
            return new Entry(Signal.CITY, city.name());
        }
        return null;
    }

    /**
     * "মিরপুরে" → "মিরপুর", "দামে" → "দাম", "dhanmondite" → "dhanmondi": strip one grammatical suffix
     * only when the result is a word the lexicon (or an area name) actually knows.
     */
    private String canonicalWord(String word, SearchReferenceData.Snapshot ref) {
        if (isKnown(word, ref)) {
            return word;
        }
        List<String> suffixes = SearchText.hasBengali(word) ? lexicon.banglaSuffixes() : lexicon.latinSuffixes();
        for (String suffix : suffixes) {
            if (word.length() - suffix.length() >= 2 && word.endsWith(suffix)) {
                String stem = word.substring(0, word.length() - suffix.length());
                if (isKnown(stem, ref)) {
                    return stem;
                }
            }
        }
        return word;
    }

    private boolean isKnown(String word, SearchReferenceData.Snapshot ref) {
        return lexicon.isKnownWord(word) || ref.areasByLowerName().containsKey(word)
                || ref.citiesByLowerName().containsKey(word);
    }

    /**
     * Nearest correctable lexicon word within 1 edit (4-5 letters) or 2 edits (6+) — and only when the
     * nearest is unambiguous, so "biriyanni" → "biriyani" but a word equidistant from two targets is
     * left alone (and then treated as part of a business name).
     */
    String typoCorrect(String word) {
        if (word.length() < 4 || SearchText.hasBengali(word)) {
            return null;
        }
        int max = word.length() <= 5 ? 1 : 2;
        String best = null;
        int bestDistance = max + 1;
        boolean tie = false;
        for (String candidate : lexicon.correctable()) {
            int d = SearchText.editDistance(word, candidate, max);
            if (d < bestDistance) {
                best = candidate;
                bestDistance = d;
                tie = false;
            } else if (d == bestDistance && d <= max && !sameMeaning(best, candidate)) {
                tie = true;
            }
        }
        return best != null && bestDistance <= max && !tie ? best : null;
    }

    private boolean sameMeaning(String a, String b) {
        if (a == null) {
            return false;
        }
        Entry ea = lexicon.lookup(a);
        Entry eb = lexicon.lookup(b);
        return ea != null && ea.equals(eb);
    }

}
