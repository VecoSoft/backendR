package com.bdreview.platform.offer;

import java.math.BigDecimal;

/**
 * The one active offer a business search/list card shows as a badge — a lean
 * projection of Offer, not the full OfferResponse (which carries far more than a
 * card needs). See BusinessResponse#activeOffer and OfferService#activeOfferSummariesByBusiness.
 */
public record ActiveOfferSummary(String title, OfferType offerType, BigDecimal discountValue) {

    public static ActiveOfferSummary from(Offer offer) {
        return new ActiveOfferSummary(offer.getTitle(), offer.getOfferType(), offer.getDiscountValue());
    }
}
