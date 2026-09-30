package com.bdreview.platform.search;

import com.bdreview.platform.search.SearchIntent.PricePreference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit coverage of intent extraction against the real lexicon file: the same need typed in
 * English, Banglish or Bangla must resolve to the same concepts/location/price, typos are corrected
 * only toward known words, and unknown words fall through as free terms rather than being guessed.
 */
class SearchIntentParserTest {

    static final UUID DHAKA = UUID.randomUUID();
    static final UUID MIRPUR = UUID.randomUUID();
    static final UUID DHANMONDI = UUID.randomUUID();

    SearchIntentParser parser;

    @BeforeEach
    void setUp() throws Exception {
        SearchLexicon lexicon = new SearchLexicon(new ObjectMapper());
        SearchReferenceData referenceData = mock(SearchReferenceData.class);
        var mirpur = new SearchReferenceData.AreaRef(MIRPUR, "Mirpur", DHAKA, "Dhaka", 4, 23.8, 90.36);
        var dhanmondi = new SearchReferenceData.AreaRef(DHANMONDI, "Dhanmondi", DHAKA, "Dhaka", 4, 23.74, 90.37);
        when(referenceData.get()).thenReturn(new SearchReferenceData.Snapshot(
                Map.of("mirpur", mirpur, "dhanmondi", dhanmondi),
                Map.of("dhaka", new SearchReferenceData.CityRef(DHAKA, "Dhaka", 12)),
                Map.of("RESTAURANT", 6), Map.of(), List.of(mirpur, dhanmondi), Map.of(), Instant.now()));
        parser = new SearchIntentParser(lexicon, referenceData);
    }

    private static List<String> ids(SearchIntent i) {
        return i.concepts().stream().map(SearchLexicon.Concept::id).toList();
    }

    @Test
    void englishBanglishAndBanglaSpellingsOfBiryaniAgree() {
        for (String q : List.of("biryani", "biriyani", "বিরিয়ানি", "Biryani!!")) {
            SearchIntent i = parser.parse(q);
            assertThat(ids(i)).as(q).containsExactly("biryani");
            assertThat(i.freeTerms()).as(q).isEmpty();
        }
    }

    @Test
    void dishPlusAreaInEitherScript() {
        for (String q : List.of("biryani mirpur", "বিরিয়ানি মিরপুর", "biryani in mirpur", "মিরপুরে বিরিয়ানি")) {
            SearchIntent i = parser.parse(q);
            assertThat(ids(i)).as(q).containsExactly("biryani");
            assertThat(i.areaId()).as(q).isEqualTo(MIRPUR);
            assertThat(i.price()).as(q).isEqualTo(PricePreference.NONE);
        }
    }

    @Test
    void cheapBiryaniNearMirpurInBanglishBanglaAndEnglish() {
        for (String q : List.of("kom dami biriyani near mirpur", "কম দামে বিরিয়ানি মিরপুর", "cheap biryani near mirpur")) {
            SearchIntent i = parser.parse(q);
            assertThat(ids(i)).as(q).containsExactly("biryani");
            assertThat(i.areaId()).as(q).isEqualTo(MIRPUR);
            assertThat(i.price()).as(q).isEqualTo(PricePreference.LOW);
            assertThat(i.nearMe()).as(q).isFalse();
            assertThat(i.freeTerms()).as(q).isEmpty();
        }
    }

    @Test
    void salonDhanmondiInBothScripts() {
        for (String q : List.of("salon dhanmondi", "সেলুন ধানমন্ডি", "best beauty parlour in dhanmondi")) {
            SearchIntent i = parser.parse(q);
            assertThat(ids(i)).as(q).containsExactly("salon");
            assertThat(i.areaId()).as(q).isEqualTo(DHANMONDI);
        }
        assertThat(parser.parse("best beauty parlour in dhanmondi").ratingHigh()).isTrue();
    }

    @Test
    void knownAreaWithoutListingsIsRecognisedButUnresolved() {
        SearchIntent i = parser.parse("good dental clinic uttara");
        assertThat(ids(i)).containsExactly("dental");
        assertThat(i.ratingHigh()).isTrue();
        assertThat(i.areaName()).isEqualTo("Uttara");
        assertThat(i.areaId()).isNull();
    }

    @Test
    void nearMeAndOpenNow() {
        SearchIntent gym = parser.parse("gym near me");
        assertThat(ids(gym)).containsExactly("gym");
        assertThat(gym.nearMe()).isTrue();
        assertThat(gym.areaName()).isNull();

        SearchIntent open = parser.parse("আমার কাছে এখন খোলা ফার্মেসি");
        assertThat(ids(open)).containsExactly("pharmacy");
        assertThat(open.nearMe()).isTrue();
        assertThat(open.openNow()).isTrue();
    }

    @Test
    void typosAreCorrectedTowardKnownWordsOnly() {
        SearchIntent i = parser.parse("biryanni mirpru");
        assertThat(ids(i)).containsExactly("biryani");
        assertThat(i.areaId()).isEqualTo(MIRPUR);
        assertThat(i.correctedQuery()).isEqualTo("biryani mirpur");

        assertThat(ids(parser.parse("salom"))).containsExactly("salon");
    }

    @Test
    void unknownWordsBecomeFreeTermsNotGuesses() {
        SearchIntent i = parser.parse("kfc gulshan");
        assertThat(i.concepts()).isEmpty();
        assertThat(i.freeTerms()).containsExactly("kfc");
        assertThat(i.areaName()).isEqualTo("Gulshan");

        SearchIntent nonsense = parser.parse("xyzzy qwerty");
        assertThat(nonsense.concepts()).isEmpty();
        assertThat(nonsense.freeTerms()).containsExactly("xyzzy", "qwerty");
        assertThat(nonsense.correctedQuery()).isNull();
    }

    @Test
    void emptyAndHostileInputAreSafe() {
        assertThat(parser.parse("").isEmpty()).isTrue();
        assertThat(parser.parse(null).isEmpty()).isTrue();
        assertThat(parser.parse("   %%% ___ ';--").isEmpty()).isTrue();

        SearchIntent injection = parser.parse("biryani' OR 1=1; DROP TABLE business;--");
        assertThat(ids(injection)).containsExactly("biryani");
        assertThat(injection.freeTerms()).allMatch(t -> t.matches("[\\p{L}\\p{N}]+"));

        SearchIntent huge = parser.parse("biryani ".repeat(500));
        assertThat(huge.normalizedQuery().length()).isLessThanOrEqualTo(SearchText.MAX_QUERY_CHARS);
    }
}
