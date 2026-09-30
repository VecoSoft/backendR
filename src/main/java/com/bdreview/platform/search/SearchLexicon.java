package com.bdreview.platform.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The smart-search vocabulary, loaded once from classpath:search/search-lexicon.json (edit that file,
 * not this class, to teach search a new word). Every alias is run through {@link SearchText#normalize}
 * so lookups compare like with like, then indexed as one flat phrase → meaning map the parser walks
 * with a longest-phrase-first scan.
 */
@Component
public class SearchLexicon {

    public enum Signal { CONCEPT, AREA, CITY, PRICE_LOW, PRICE_HIGH, RATING_HIGH, NEAR_ME, OPEN_NOW, STOP }

    public enum ConceptType { CATEGORY, ITEM }

    public record Concept(String id, ConceptType type, String kind, String icon, String labelEn, String labelBn,
                          List<String> aliases) {
        public String label(String lang) {
            return "bn".equals(lang) ? labelBn : labelEn;
        }
    }

    /** One indexed phrase. `value` is the concept id / canonical area or city name; null for pure signals. */
    public record Entry(Signal signal, String value) {
    }

    /** Canonical area/city name + its Bangla spelling (first Bengali alias), used in Bangla notices. */
    public record Place(String name, String bnName) {
    }

    private final Map<String, Entry> phrases = new HashMap<>();
    private final Map<String, Concept> concepts = new LinkedHashMap<>();
    private final Map<String, Place> areas = new LinkedHashMap<>();
    private final Map<String, Place> cities = new LinkedHashMap<>();
    /** Every single word that appears in any phrase — the target set for suffix stripping. */
    private final Set<String> knownWords = new HashSet<>();
    /** Single-word Latin aliases worth typo-correcting towards (concepts, areas, cities; 4+ letters). */
    private final List<String> correctable = new ArrayList<>();
    private final List<String> banglaSuffixes = new ArrayList<>();
    private final List<String> latinSuffixes = new ArrayList<>();
    private int maxPhraseWords = 1;

    public SearchLexicon(ObjectMapper objectMapper) throws IOException {
        try (InputStream in = new ClassPathResource("search/search-lexicon.json").getInputStream()) {
            load(objectMapper.readTree(in));
        }
    }

    private void load(JsonNode root) {
        for (JsonNode c : root.path("concepts")) {
            List<String> aliases = new ArrayList<>();
            c.path("aliases").forEach(a -> aliases.add(SearchText.normalize(a.asText())));
            Concept concept = new Concept(c.path("id").asText(), ConceptType.valueOf(c.path("type").asText()),
                    c.path("kind").asText(), c.path("icon").asText(), c.path("label").path("en").asText(),
                    c.path("label").path("bn").asText(), List.copyOf(aliases));
            concepts.put(concept.id(), concept);
            aliases.forEach(a -> index(a, new Entry(Signal.CONCEPT, concept.id()), true));
        }
        loadPlaces(root.path("areas"), Signal.AREA, areas);
        loadPlaces(root.path("cities"), Signal.CITY, cities);
        indexAll(root.path("priceLow"), Signal.PRICE_LOW);
        indexAll(root.path("priceHigh"), Signal.PRICE_HIGH);
        indexAll(root.path("ratingHigh"), Signal.RATING_HIGH);
        indexAll(root.path("nearMe"), Signal.NEAR_ME);
        indexAll(root.path("openNow"), Signal.OPEN_NOW);
        indexAll(root.path("stopwords"), Signal.STOP);
        root.path("banglaSuffixes").forEach(s -> banglaSuffixes.add(SearchText.normalize(s.asText())));
        root.path("latinSuffixes").forEach(s -> latinSuffixes.add(SearchText.normalize(s.asText())));
    }

    private void loadPlaces(JsonNode list, Signal signal, Map<String, Place> target) {
        for (JsonNode p : list) {
            String name = p.path("name").asText();
            String bn = null;
            for (JsonNode a : p.path("aliases")) {
                String alias = SearchText.normalize(a.asText());
                if (bn == null && SearchText.hasBengali(alias)) {
                    bn = alias;
                }
                index(alias, new Entry(signal, name), true);
            }
            target.put(name.toLowerCase(), new Place(name, bn != null ? bn : name));
        }
    }

    private void indexAll(JsonNode list, Signal signal) {
        list.forEach(a -> index(SearchText.normalize(a.asText()), new Entry(signal, null), false));
    }

    private void index(String phrase, Entry entry, boolean correctableTarget) {
        if (phrase.isEmpty()) {
            return;
        }
        // First definition wins, so a phrase listed under a concept is never shadowed by a stopword.
        phrases.putIfAbsent(phrase, entry);
        String[] words = phrase.split(" ");
        maxPhraseWords = Math.max(maxPhraseWords, words.length);
        Collections.addAll(knownWords, words);
        if (correctableTarget && words.length == 1 && phrase.length() >= 4 && !SearchText.hasBengali(phrase)) {
            correctable.add(phrase);
        }
    }

    public Entry lookup(String phrase) {
        return phrases.get(phrase);
    }

    public Concept concept(String id) {
        return concepts.get(id);
    }

    public List<Concept> concepts() {
        return List.copyOf(concepts.values());
    }

    public Place area(String canonicalName) {
        return canonicalName == null ? null : areas.get(canonicalName.toLowerCase());
    }

    public Place city(String canonicalName) {
        return canonicalName == null ? null : cities.get(canonicalName.toLowerCase());
    }

    public Map<String, Place> areas() {
        return Collections.unmodifiableMap(areas);
    }

    public boolean isKnownWord(String word) {
        return knownWords.contains(word);
    }

    public List<String> correctable() {
        return Collections.unmodifiableList(correctable);
    }

    public List<String> banglaSuffixes() {
        return Collections.unmodifiableList(banglaSuffixes);
    }

    public List<String> latinSuffixes() {
        return Collections.unmodifiableList(latinSuffixes);
    }

    public int maxPhraseWords() {
        return maxPhraseWords;
    }

    /** All indexed phrases — used by suggestions for prefix completion. */
    public Map<String, Entry> phrases() {
        return Collections.unmodifiableMap(phrases);
    }
}
