package com.bdreview.platform.business;

import com.bdreview.platform.catalog.CategoryModuleFlags;

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
        List<HoursExceptionEntry> hoursExceptions
) {
    /** photoUrls: cover photo (if any) followed by gallery photos, in display order — card carousel source. */
    public static BusinessResponse from(Business b, List<String> photoUrls, boolean claimed) {
        return from(b, photoUrls, claimed, null, null, null, null, null);
    }

    /** Detail-view variant: also carries the category-module presence flags, the updates flag, the FAQ flag, structured hours, and hours exceptions. */
    public static BusinessResponse from(Business b, List<String> photoUrls, boolean claimed,
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
                b.isFlagged(), b.getFlagReason(), b.getFlaggedAt(),
                b.getTotalLikeCount(), b.getTotalDislikeCount(), b.getTotalLoveCount(), b.getTotalWowCount(),
                categoryModules, hasUpdates, hasFaq, structuredHours, hoursExceptions
        );
    }
}
