package com.bdreview.platform.catalog;

/**
 * "Which category modules actually have data" — computed once for the business
 * detail response so the public page can decide which tabs to show WITHOUT
 * pulling every module's rows up front. Null on list/search responses (only the
 * detail view needs it); each tab fetches its own list when opened.
 */
public record CategoryModuleFlags(
        boolean hasOfferings,
        boolean hasFacilities,
        boolean hasTeam,
        boolean hasMenu,
        boolean hasProducts
) {
}
