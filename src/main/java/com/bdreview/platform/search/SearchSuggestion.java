package com.bdreview.platform.search;

/**
 * One row in the search box's dropdown (GET /api/v1/businesses/search-suggestions).
 *
 * @param type  CONCEPT ("Biryani"), CONCEPT_AREA ("Biryani in Mirpur"), CONCEPT_NEAR ("Biryani near me"),
 *              AREA ("Mirpur"), BUSINESS (a specific listing — `slug` set, the client navigates straight to it)
 *              or POPULAR (shown for an empty box)
 * @param query the text to run as a search when this row is picked
 * @param detail optional secondary text (e.g. the business's area)
 */
public record SearchSuggestion(String type, String label, String query, String icon, String slug, String detail) {
}
