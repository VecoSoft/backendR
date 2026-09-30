package com.bdreview.platform.search;

import com.bdreview.platform.business.BusinessResponse;
import com.bdreview.platform.common.PageResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GET /api/v1/businesses/smart-search. Deliberately carries no scores or internal ranking data —
 * only what the page shows: the results, how the query was understood, an honest notice when a
 * constraint had to be relaxed, and up to two short "why this matched" labels per result.
 *
 * @param notice        human-readable, already localised; null when results match the query exactly
 * @param needsLocation the query asked for "near me" but no coordinates were sent — the client may
 *                      offer its consent-based location prompt; results are unranked by distance
 * @param matchReasons  business id → short labels such as "Menu: Chicken Biryani" or "2.4 km from Mirpur"
 */
public record SmartSearchResponse(
        PageResponse<BusinessResponse> results,
        IntentSummary intent,
        String notice,
        boolean needsLocation,
        Map<UUID, List<String>> matchReasons,
        /**
         * V58: at most one paid placement for this query, shown ABOVE the organic results with a
         * "Sponsored" label. Kept separate on purpose — {@link #results} is byte-for-byte what the
         * query would return with no boosts at all. Null on later pages and when nothing qualifies.
         */
        com.bdreview.platform.promo.SponsoredService.SponsoredBusiness sponsored) {

    /** One recognised thing the query asked for, e.g. {label: "Biryani", icon: "🍛"}. */
    public record IntentChip(String label, String icon) {
    }

    /**
     * @param location        area/city the query named (canonical spelling), or null
     * @param locationKnown   false when the place was recognised but has no listings yet (e.g. Uttara)
     * @param price           "LOW" / "HIGH" / null
     * @param correctedQuery  shown as "Showing results for …" when a typo was fixed; null otherwise
     */
    public record IntentSummary(List<IntentChip> what, List<String> keywords, String location, boolean locationKnown,
                                String price, boolean ratingHigh, boolean nearMe, boolean openNow,
                                String correctedQuery) {
    }
}
