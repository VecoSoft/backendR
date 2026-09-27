package com.bdreview.platform.business;

import com.bdreview.platform.catalog.CategoryModuleFlags;
import com.bdreview.platform.offer.ActiveOfferSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BusinessResponse(
        UUID id,
        UUID ownerUserId,
        String name,
        String slug,
        String categoryName,
        /** Phase 2 — canonical classification driving category-specific modules. */
        CategoryKind categoryKind,
        String cityName,
        String areaName,
        String contactNumber,
        String operatingHours,
        String description,
        /** Optional trust-building field, "Since {establishedYear}" — null when unset. */
        Integer establishedYear,
        String coverPhotoUrl,
        String logoUrl,
        /** "Business presence" (spec Step 4) — null when unset; the public page omits empty ones. */
        String websiteUrl,
        String whatsappNumber,
        String email,
        String facebookUrl,
        String instagramUrl,
        List<String> photoUrls,
        double latitude,
        double longitude,
        PriceTier priceTier,
        List<String> attributes,
        boolean verified,
        /** True once a real (non-admin) owner has claimed this listing — see BusinessClaimService#ensureClaimable. */
        boolean claimed,
        BigDecimal averageRating,
        int reviewCount,
        /**
         * Non-null only when this listing is part of a chain. Unlike categoryModules/hasUpdates/etc
         * below, these are populated on BOTH search/list AND detail responses — the search grid
         * needs brandId/branchCount to decide whether to render a grouped "N branches" card, and
         * the detail page's branch-switcher pill needs them too.
         */
        UUID brandId,
        String brandName,
        String brandSlug,
        /** True total live branch count for the brand (not just how many are on the current search page). */
        Integer branchCount,
        /** Combined rating across every live branch of the brand — SUM(rating_sum)/SUM(review_count) rounded the same way as the per-business rollup. Null when brandId is null. */
        BigDecimal brandAverageRating,
        boolean flagged,
        String flagReason,
        Instant flaggedAt,
        int totalLikeCount,
        int totalDislikeCount,
        int totalLoveCount,
        int totalWowCount,
        /**
         * Which category modules actually have data — populated on the single-business
         * detail response only (GET /businesses/{slug}); null on list/search results.
         * Lets the public page pick tabs without loading every module's rows.
         */
        CategoryModuleFlags categoryModules,
        /** True if the listing has ≥1 published update — detail response only, null on lists. */
        Boolean hasUpdates,
        /** True if the listing has ≥1 FAQ entry — detail response only, null on lists. Lets the public page decide whether "About" needs to show even with no other about-data. */
        Boolean hasFaq,
        /**
         * Structured per-day hours (V38) — detail response only, null on list/search
         * results to avoid an extra lookup per row on the native-SQL search path.
         * Null/empty means the business hasn't set structured hours (legacy free-text
         * {@code operatingHours} only); the frontend shows no "open now" badge in that case.
         */
        List<OperatingHoursEntry> structuredHours,
        /**
         * Holiday / special-hours overrides (V41) — detail response only, same
         * null-on-list convention as structuredHours. An active exception always
         * takes precedence over the recurring weekly entry for "is it open" purposes.
         */
        List<HoursExceptionEntry> hoursExceptions,
        /** Populated on every response (detail and list/search alike) — a plain entity column, free to include. */
        Instant createdAt,
        /**
         * A short excerpt of the business's top review — search/list responses only (via the
         * search()-specific from() overload below, batched across the page); null everywhere
         * else, including detail (which shows every review itself, so a single excerpt adds
         * nothing there).
         */
        String topReviewSnippet,
        /** The one active offer to show as a card badge — same search/list-only, batched convention as topReviewSnippet. */
        ActiveOfferSummary activeOffer
) {
    /** photoUrls: cover photo (if any) followed by gallery photos, in display order — card carousel source. */
    public static BusinessResponse from(Business b, List<String> photoUrls, boolean claimed) {
        return from(b, photoUrls, claimed, null, null, null, null, null, null);
    }

    /** Same as the 3-arg overload, plus the brand summary (search/list responses — no detail-only fields). */
    public static BusinessResponse from(Business b, List<String> photoUrls, boolean claimed, BrandSummary brand) {
        return from(b, photoUrls, claimed, brand, null, null, null, null, null);
    }

    /**
     * The search()-only variant: also carries structured hours/exceptions (for "open now"),
     * a batched top review snippet, and a batched active-offer summary — see
     * BusinessService#search, which fetches all three as one query each for the whole result
     * page, never per row.
     */
    public static BusinessResponse from(Business b, List<String> photoUrls, boolean claimed, BrandSummary brand,
                                        List<OperatingHoursEntry> structuredHours, List<HoursExceptionEntry> hoursExceptions,
                                        String topReviewSnippet, ActiveOfferSummary activeOffer) {
        return new BusinessResponse(
                b.getId(), b.getOwnerUserId(), b.getName(), b.getSlug(),
                b.getCategory().getName(), b.getCategory().getKind(),
                b.getCity().getName(), b.getArea().getName(),
                b.getContactNumber(), b.getOperatingHours(), b.getDescription(), b.getEstablishedYear(), b.getCoverPhotoUrl(),
                b.getLogoUrl(),
                b.getWebsiteUrl(), b.getWhatsappNumber(), b.getEmail(), b.getFacebookUrl(), b.getInstagramUrl(),
                photoUrls,
                b.getLocation().getY(), b.getLocation().getX(),
                b.getPriceTier(),
                b.getAttributes().stream().map(BusinessAttribute::getName).toList(),
                b.isVerified(), claimed, b.getAverageRating(), b.getReviewCount(),
                brand != null ? brand.id() : null, brand != null ? brand.name() : null,
                brand != null ? brand.slug() : null, brand != null ? brand.branchCount() : null,
                brand != null ? brand.averageRating() : null,
                b.isFlagged(), b.getFlagReason(), b.getFlaggedAt(),
                b.getTotalLikeCount(), b.getTotalDislikeCount(), b.getTotalLoveCount(), b.getTotalWowCount(),
                null, null, null, structuredHours, hoursExceptions,
                b.getCreatedAt(), topReviewSnippet, activeOffer
        );
    }

    /** Detail-view variant: also carries the category-module presence flags, the updates flag, the FAQ flag, structured hours, and hours exceptions. */
    public static BusinessResponse from(Business b, List<String> photoUrls, boolean claimed, BrandSummary brand,
                                        CategoryModuleFlags categoryModules, Boolean hasUpdates, Boolean hasFaq,
                                        List<OperatingHoursEntry> structuredHours,
                                        List<HoursExceptionEntry> hoursExceptions) {
        return new BusinessResponse(
                b.getId(), b.getOwnerUserId(), b.getName(), b.getSlug(),
                b.getCategory().getName(), b.getCategory().getKind(),
                b.getCity().getName(), b.getArea().getName(),
                b.getContactNumber(), b.getOperatingHours(), b.getDescription(), b.getEstablishedYear(), b.getCoverPhotoUrl(),
                b.getLogoUrl(),
                b.getWebsiteUrl(), b.getWhatsappNumber(), b.getEmail(), b.getFacebookUrl(), b.getInstagramUrl(),
                photoUrls,
                b.getLocation().getY(), b.getLocation().getX(),
                b.getPriceTier(),
                b.getAttributes().stream().map(BusinessAttribute::getName).toList(),
                b.isVerified(), claimed, b.getAverageRating(), b.getReviewCount(),
                brand != null ? brand.id() : null, brand != null ? brand.name() : null,
                brand != null ? brand.slug() : null, brand != null ? brand.branchCount() : null,
                brand != null ? brand.averageRating() : null,
                b.isFlagged(), b.getFlagReason(), b.getFlaggedAt(),
                b.getTotalLikeCount(), b.getTotalDislikeCount(), b.getTotalLoveCount(), b.getTotalWowCount(),
                categoryModules, hasUpdates, hasFaq, structuredHours, hoursExceptions,
                b.getCreatedAt(), null, null
        );
    }
}
