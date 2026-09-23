package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A user following a specific QUESTION post — distinct from {@link CommunityFollow}
 * (user-follows-user). Powers the "Questions for you" widget's Follow button, its
 * per-question follower count, and the "Last followed" timestamp shown on a card.
 */
@Entity
@Table(name = "community_question_follow", uniqueConstraints =
        @UniqueConstraint(columnNames = {"user_id", "post_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityQuestionFollow {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
