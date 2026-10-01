package com.bdreview.platform.promo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Everything a creative template needs to draw itself (V58) — the single source of truth for
 * both the Studio's live HTML preview and the PNG renderer (frontend/src/components/promo).
 * Owner choices (headline, subline, colour, photo, toggles) come from the creative; everything
 * factual (prices, offer terms, validity, rating, review quote) is read from the database at the
 * moment this model is built, so a creative can never advertise a stale price or an ended offer.
 */
public record PromoRenderModel(
        String templateKey,
        String businessName,
        String logoUrl,
        String areaName,
        String cityName,
        /** Null when the business has no reviews yet — templates then hide rating UI entirely. */
        BigDecimal averageRating,
        int reviewCount,
        /** A real review (≥4★) with the reviewer's first name only; null when none qualifies. */
        Quote quote,
        String headline,
        String subline,
        String accentColor,
        String photoUrl,
        boolean showRating,
        boolean showQr,
        boolean showPrice,
        Offer offer,
        MenuItem menuItem,
        Event event,
        /** The QR/"scan me" target — the business page with ?ref=promo_&lt;creativeId&gt;. */
        String shareUrl,
        /** The linked offer has ended — share pages overlay "Expired". */
        boolean expired,
        /** V61: "FIT" or "FILL" — how the CUSTOM template places the owner's uploaded banner. */
        String imageFit) {

    public record Quote(String text, String firstName, int rating) {
    }

    /** {@code headlineText} is the big "20% OFF" / "BUY 1 GET 1" line, derived from the offer's type. */
    public record Offer(String title, String headlineText, BigDecimal originalPrice, BigDecimal offerPrice,
                        Instant validUntil, boolean active) {
    }

    public record MenuItem(String name, BigDecimal price, String priceText, String photoUrl) {
    }

    public record Event(String title, Instant start, Instant end, String location) {
    }
}
