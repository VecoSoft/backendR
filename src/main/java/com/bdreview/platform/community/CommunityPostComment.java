package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A comment on a CommunityPost, optionally a reply to another comment
 * (Reddit-style threaded discussion — see V33 migration). Storage stays
 * flat (parentCommentId + depth) rather than a recursive tree fetch; the
 * frontend renders the nesting from depth/parentCommentId. Depth is capped
 * at 5 in CommunityPostService#addComment. Soft-deleted like Review.
 */
@Entity
@Table(name = "community_post_comment")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityPostComment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "author_user_id", nullable = false)
    private UUID authorUserId;

    /** V58: a business replying as itself — only ever on that business's own posts. */
    @Column(name = "author_business_id")
    private UUID authorBusinessId;

    /** Null for a top-level comment on the post itself. */
    @Column(name = "parent_comment_id")
    private UUID parentCommentId;

    @Builder.Default
    @Column(nullable = false)
    private short depth = 0;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /**
     * At most one true per post (enforced by a partial unique index — see
     * V35). Only meaningful for a top-level (depth 0) comment on a QUESTION
     * post — see CommunityPostService#markBestAnswer.
     */
    @Builder.Default
    @Column(name = "is_best_answer", nullable = false)
    private boolean bestAnswer = false;

    @Builder.Default
    @Column(name = "upvote_count", nullable = false)
    private int upvoteCount = 0;

    @Builder.Default
    @Column(name = "downvote_count", nullable = false)
    private int downvoteCount = 0;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // ---- Moderation state (V56) — same shape as CommunityPost ----

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommunityContentStatus status = CommunityContentStatus.ACTIVE;

    @Column(name = "hold_reason", length = 40)
    private String holdReason;

    @Column(name = "removed_by")
    private UUID removedBy;

    @Column(name = "removed_reason", columnDefinition = "text")
    private String removedReason;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Builder.Default
    @Column(name = "report_count", nullable = false)
    private int reportCount = 0;

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
}
