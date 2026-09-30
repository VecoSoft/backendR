package com.bdreview.platform.promo;

/** Value sets for the promotion module (V58) — kept together; each mirrors a CHECK constraint. */
public final class PromoEnums {

    private PromoEnums() {
    }

    public enum BusinessPostType { MENU_ITEM, OFFER, EVENT, ANNOUNCEMENT, GENERAL }

    /**
     * Promotion lifecycle, stored on business_post. The community_post row mirrors it for
     * visibility: DRAFT→DRAFT, PENDING_REVIEW→PENDING, PUBLISHED/EXPIRED→ACTIVE (an expired post
     * stays readable with an "Expired" label but never takes sponsored slots),
     * REJECTED/REMOVED→REMOVED.
     */
    public enum BusinessPostStatus { DRAFT, PENDING_REVIEW, PUBLISHED, REJECTED, REMOVED, EXPIRED }

    public enum BoostStatus { PENDING_PAYMENT, PENDING_REVIEW, ACTIVE, PAUSED, ENDED, REJECTED, REFUNDED }

    public enum PaymentMethod { BKASH, NAGAD, MANUAL }

    public enum PromoEventType { IMPRESSION, CLICK, PROFILE_VISIT, CALL, DIRECTIONS, MESSAGE, ORDER, BOOKING, OFFER_CLAIM, SHARE }

    public enum PromoSource { FEED, HOME, SEARCH, SHARE_LINK, EXTERNAL }

    public enum CreativeFormat {
        SQUARE(1080, 1080), STORY(1080, 1920), OG(1200, 630);

        public final int width;
        public final int height;

        CreativeFormat(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
