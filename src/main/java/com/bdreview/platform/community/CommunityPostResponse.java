package com.bdreview.platform.community;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommunityPostResponse(
        UUID id,
        CommunityAuthorSummary author,
        /** Null for a REMOVED post — its content is never sent. */
        String title,
        String body,
        /** The post's photo attachments, in order; a legacy pre-V1 post's single image (if any) comes through as a 1-entry list. Empty if none. */
        List<String> imageUrls,
        CommunityPostType postType,
        /** A community_topic code ("FOOD", ...) — same values the enum used to produce. */
        String topic,
        CommunityAreaSummary area,
        int upvoteCount,
        int downvoteCount,
        int score,
        /** Null if the current viewer hasn't voted (or is anonymous). */
        CommunityPostVoteType myVote,
        int commentCount,
        /** 0 or 1 entries for a V1 post (single optional business attach); legacy posts may carry more. */
        List<CommunityMentionedBusinessSummary> mentionedBusinesses,
        /** Non-null only when postType == POLL. */
        CommunityPollResponse poll,
        /** Non-null only when postType == QUESTION — derived, see CommunityPostService#questionStatus. */
        CommunityQuestionStatus questionStatus,
        /** Top-level (depth 0) comments only — "Answers" on a QUESTION post. 0 for other post types. */
        int answerCount,
        Instant createdAt,
        Instant updatedAt,
        // ---- V56 moderation fields (additive) ----
        /** ACTIVE for everyone else; the author may also see PENDING/HIDDEN ("Waiting for review") or REMOVED. */
        CommunityContentStatus status,
        /** Comments are turned off. */
        boolean locked,
        boolean pinned,
        boolean featured,
        /** "Jachai Team" announcement — author is CommunityAuthorSummary.jachaiTeam(). */
        boolean official,
        /** Only sent to the post's own author, and only when status == REMOVED. */
        String removedReason,
        // ---- V58 business promotion (additive) ----
        /** Non-null when posted AS a business; {@code author} is then a blank placeholder, never the owner's identity. */
        BusinessIdentity business,
        /** Promotion details for a business post (CTA, offer/menu refs, creative, expiry); null for member posts. */
        com.bdreview.platform.promo.BusinessPostView promotion,
        /** Non-null only on a paid placement injected into a feed — the client must label it "Sponsored". */
        com.bdreview.platform.promo.SponsoredInfo sponsored
) {
    public CommunityPostResponse withSponsored(com.bdreview.platform.promo.SponsoredInfo info) {
        return new CommunityPostResponse(id, author, title, body, imageUrls, postType, topic, area, upvoteCount,
                downvoteCount, score, myVote, commentCount, mentionedBusinesses, poll, questionStatus, answerCount,
                createdAt, updatedAt, status, locked, pinned, featured, official, removedReason, business, promotion, info);
    }
}
