package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * At most ONE vote per (poll, user) — single-choice: voting again for the
 * same option removes it, voting for a different option switches it. Same
 * toggle/swap shape as {@link CommunityPostVote}. See CommunityPostService#votePoll.
 */
@Entity
@Table(name = "community_post_poll_vote", uniqueConstraints =
        @UniqueConstraint(columnNames = {"poll_id", "user_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityPostPollVote {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "poll_id", nullable = false)
    private UUID pollId;

    @Column(name = "option_id", nullable = false)
    private UUID optionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
