package com.bdreview.platform.search;

import java.util.List;
import java.util.UUID;

/**
 * What {@link SearchIntentParser} understood from a free-text query. Internal — the public
 * projection is {@link SmartSearchResponse.IntentSummary}.
 *
 * @param concepts   recognised categories/dishes/services, in query order
 * @param freeTerms  leftover words that matched nothing in the lexicon (usually part of a business name)
 * @param areaName   canonical area name the query mentioned (may be one with no listings: areaId null)
 * @param correctedQuery the query with typo corrections applied, or null if nothing was corrected
 */
public record SearchIntent(
        String normalizedQuery,
        String correctedQuery,
        List<SearchLexicon.Concept> concepts,
        List<String> freeTerms,
        String areaName,
        UUID areaId,
        String cityName,
        UUID cityId,
        PricePreference price,
        boolean ratingHigh,
        boolean nearMe,
        boolean openNow) {

    public enum PricePreference { NONE, LOW, HIGH }

    public boolean isEmpty() {
        return concepts.isEmpty() && freeTerms.isEmpty() && areaName == null && cityName == null
                && price == PricePreference.NONE && !ratingHigh && !nearMe && !openNow;
    }

    public boolean hasWhat() {
        return !concepts.isEmpty() || !freeTerms.isEmpty();
    }

    public boolean hasItemConcepts() {
        return concepts.stream().anyMatch(c -> c.type() == SearchLexicon.ConceptType.ITEM);
    }
}
