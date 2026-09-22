package com.bdreview.platform.community;

/**
 * FOR_YOU has no personalization signal to draw on yet (no follow-topic, no
 * engagement history) so it falls back to the plain recent/top feed — same
 * query as no tab at all. See CommunityPostService#feed.
 */
public enum CommunityFeedTab {
    FOR_YOU, FOLLOWING, NEARBY
}
