package com.bdreview.platform.photomod;

/**
 * Where a moderated photo lives (V63). Multi-photo sources keep one row per photo with its own
 * {@code moderation_status}; single-field sources only get the URL copied into the live field
 * once the photo is approved.
 */
public enum PhotoSource {
    /** business_photo row; source_id = business id. */
    BUSINESS_PHOTO("Business gallery", true),
    /** business.cover_photo_url; source_id = business id. */
    COVER("Cover photo", false),
    /** business.logo_url; source_id = business id. */
    LOGO("Logo", false),
    /** business_menu_item.photo_url; source_id = menu item id. */
    MENU_ITEM("Menu item", false),
    /** community_post_photo row; source_id = post id. */
    POST("Community post", true),
    /** review_photo row; source_id = review id. */
    REVIEW("Review", true),
    /** Homepage hero image (V65); source_id = {@link #HERO_SOURCE_ID}; copied into the homepage config on approval. */
    HERO("Homepage hero", false);

    /** Fixed source id for the single homepage hero slot. */
    public static final java.util.UUID HERO_SOURCE_ID = new java.util.UUID(0L, 1L);

    private final String label;
    private final boolean rowBased;

    PhotoSource(String label, boolean rowBased) {
        this.label = label;
        this.rowBased = rowBased;
    }

    public String label() {
        return label;
    }

    public boolean rowBased() {
        return rowBased;
    }
}
