package com.bdreview.platform.promo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Promotion-specific part of a business post as the feed/detail/share page renders it. Offer and
 * menu details are read live from their own tables on every request, so a creative can never
 * show a stale price or an offer that has ended.
 *
 * @param cta     GET_OFFER | ORDER | INTERESTED | VIEW_BUSINESS — disabled by the client when {@code expired}
 * @param canBoost only ever true for the post's own business owner
 */
public record BusinessPostView(
        String type,
        String status,
        boolean expired,
        String cta,
        OfferRef offer,
        MenuItemRef menuItem,
        Instant eventStart,
        Instant eventEnd,
        UUID creativeId,
        String squareUrl,
        String storyUrl,
        String ogUrl,
        int interestedCount,
        boolean interested,
        String rejectionReason,
        boolean canBoost) {

    public record OfferRef(UUID id, String title, String offerType, BigDecimal discountValue,
                           BigDecimal originalPrice, BigDecimal offerPrice, Instant validUntil, boolean active) {
    }

    public record MenuItemRef(UUID id, String name, BigDecimal price, String priceText, String photoUrl,
                              boolean available, boolean orderable) {
    }
}
