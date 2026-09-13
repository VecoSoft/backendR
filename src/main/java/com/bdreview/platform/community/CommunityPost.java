package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * "Join Community" feed post — Facebook-style: text + at most one image
 * (no video, per spec), reactions, comments, and business mentions.
 * Soft-deleted like Review/Business so moderation/admin can still see it.
 * Reaction/comment counters are written only via atomic SQL increments in
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

    @Column(columnDefinition = "text")
    private String content;

    /** CDN URL of the single attached image, or null for a text-only post. Never a video. */
    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    @Builder.Default
    @Column(name = "like_count", nullable = false)
    private int likeCount = 0;

    @Builder.Default
    @Column(name = "love_count", nullable = false)
    private int loveCount = 0;

    @Builder.Default
    @Column(name = "haha_count", nullable = false)
    private int hahaCount = 0;

    @Builder.Default
    @Column(name = "wow_count", nullable = false)
    private int wowCount = 0;

    @Builder.Default
    @Column(name = "sad_count", nullable = false)
    private int sadCount = 0;

    @Builder.Default
    @Column(name = "angry_count", nullable = false)
    private int angryCount = 0;

    @Builder.Default
    @Column(name = "comment_count", nullable = false)
    private int commentCount = 0;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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
    public int getTotalReactionCount() {
        return likeCount + loveCount + hahaCount + wowCount + sadCount + angryCount;
    }
}
