package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * "Join Community" post — Reddit-style title+body text discussion (V1;
 * formerly a Facebook-style content+image feed, see V33 migration). At most
 * one optional Business and one optional Area may be attached. Soft-deleted
 * like Review/Business so moderation/admin can still see it. Vote/comment
 * counters are written only via atomic SQL increments in
 * {@link CommunityPostRepository} — never read-modify-write — same
 * convention as Business#averageRating / Review#usefulCount.
 */
@Entity
@Table(name = "community_post")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityPost {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "author_user_id", nullable = false)
    private UUID authorUserId;

    /**
     * V58: set when the post is published AS a business (promo.BusinessPostService). The owner's
     * pseudonymous community identity is then never exposed on the post — see
     * CommunityPostService#assembleResponse.
     */
    @Column(name = "author_business_id")
    private UUID authorBusinessId;

    /** Nullable only for pre-V1 legacy posts (never required going forward — see CreateCommunityPostRequest). */
    @Column(length = 150)
    private String title;

    /** Renamed from `content` in V33. Optional — a title-only post is valid. */
    @Column(columnDefinition = "text")
    private String body;

    /**
     * CDN URL of a single legacy image (pre-V1 Facebook-style posts only,
     * see V33). Never written by new posts — a V1 post's photo attachments
     * (0 or more) live in community_post_photo instead (see V43); read-side
     * assembly falls back to this column only when that table has no rows
     * for the post, so old rows stay visible without a data migration.
     */
    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", nullable = false, length = 20)
    private CommunityPostType postType = CommunityPostType.DISCUSSION;

    /** A community_topic.code (V56 — was the CommunityTopic enum); same string values on the wire. */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String topic = "GENERAL";

    /** Optional — powers the "Nearby" feed. Set explicitly by the author or inferred from an attached business. */
    @Column(name = "area_id")
    private UUID areaId;

    @Builder.Default
    @Column(name = "upvote_count", nullable = false)
    private int upvoteCount = 0;

    @Builder.Default
    @Column(name = "downvote_count", nullable = false)
    private int downvoteCount = 0;

    @Builder.Default
    @Column(name = "comment_count", nullable = false)
    private int commentCount = 0;

    /** Top-level (depth 0) comments only — "Answers" on a QUESTION post. Distinct from commentCount, which is the total thread size including nested replies. */
    @Builder.Default
    @Column(name = "answer_count", nullable = false)
    private int answerCount = 0;

    /** Set only by the question's own author (see CommunityPostService#closeQuestion). Null = still open (or not a QUESTION post). */
    @Column(name = "closed_at")
    private Instant closedAt;

    /** Author's own "delete my post" — distinct from a moderator removal (status REMOVED, restorable). */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    // ---- Moderation state (V56) ----

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommunityContentStatus status = CommunityContentStatus.ACTIVE;

    /** Why a post is PENDING/HIDDEN (NEW_USER, BANNED_WORD, LINKS, REPORTS) — null otherwise. */
    @Column(name = "hold_reason", length = 40)
    private String holdReason;

    @Column(name = "removed_by")
    private UUID removedBy;

    @Column(name = "removed_reason", columnDefinition = "text")
    private String removedReason;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean locked = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean pinned = false;

    /** GLOBAL, TOPIC (pinned within its own topic) or AREA (pinned within its own area's Nearby feed). */
    @Column(name = "pin_scope", length = 10)
    private String pinScope;

    @Column(name = "pinned_until")
    private Instant pinnedUntil;

    @Builder.Default
    @Column(nullable = false)
    private boolean featured = false;

    /** "Jachai Team" announcement — see community.moderation.CommunityAnnouncementService. */
    @Builder.Default
    @Column(nullable = false)
    private boolean official = false;

    /** Scheduled announcement window; null = no bound. */
    @Column(name = "visible_from")
    private Instant visibleFrom;

    @Column(name = "visible_until")
    private Instant visibleUntil;

    @Builder.Default
    @Column(name = "report_count", nullable = false)
    private int reportCount = 0;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    @Transient
    public int getScore() {
        return upvoteCount - downvoteCount;
    }

    @Transient
    public boolean isPinnedNow() {
        return pinned && (pinnedUntil == null || pinnedUntil.isAfter(Instant.now()));
    }
}
