package com.bdreview.platform.community;

import com.bdreview.platform.promo.BusinessPostView;
import com.bdreview.platform.promo.SponsoredInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V58 seam between the community feed and the promotion module (implemented by
 * promo.CommunityPromotionBridge). Kept as an interface so CommunityPostService never depends on
 * promotion internals and keeps working unchanged when no implementation is present.
 */
public interface BusinessPostSupport {

    /** Promotion details (type, CTA, offer/menu refs, creative, expiry) for the business posts among {@code postIds}. */
    Map<UUID, BusinessPostView> views(Collection<UUID> postIds, UUID viewerUserId);

    /**
     * Up to {@code count} eligible sponsored business posts for one feed page — already filtered
     * by targeting, pacing and the viewer's frequency cap, and never any of {@code excludePostIds}.
     */
    List<SponsoredSlot> sponsoredForFeed(FeedViewer viewer, int count, Collection<UUID> excludePostIds);

    /** How many organic posts must sit between sponsored ones (admin setting, default 8); 0 = sponsored feed items off. */
    int sponsoredFeedRatio();

    /** Where the viewer is, as far as the viewer has chosen to tell us. Every field is optional. */
    record FeedViewer(UUID viewerUserId, UUID areaId, Double lat, Double lng, String sessionId) {
    }

    record SponsoredSlot(UUID postId, SponsoredInfo info) {
    }
}
