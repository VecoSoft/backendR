package com.bdreview.platform.offer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OfferResponse(
        UUID id,
        UUID businessId,
        String businessName,
        String businessSlug,
        String businessLogoUrl,
        boolean businessVerified,
        BigDecimal businessAverageRating,
        int businessReviewCount,
        String areaName,
        String cityName,
        String title,
        OfferType offerType,
        BigDecimal discountValue,
        BigDecimal originalPrice,
        BigDecimal offerPrice,
        String description,
        String termsAndConditions,
        String imageUrl,
        Instant validFrom,
        Instant validUntil,
        OfferAvailability availability,
        /** The owner/admin-driven stored value — see OfferStatus's own doc comment. */
        OfferStatus status,
        /** What the UI should actually show — EXPIRED overrides a stale ACTIVE once past validUntil. */
        OfferStatus effectiveStatus,
        Integer maxTotalRedemptions,
        Integer maxRedemptionsPerUser,
        int viewCount,
        int claimCount,
        int redemptionCount,
        String rejectionReason,
        /** Whether the current viewer has saved this offer — false for an anonymous viewer. */
        boolean saved,
        Instant createdAt,
        Instant updatedAt
) {
}
