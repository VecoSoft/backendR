package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A poll attached to a POST_TYPE=POLL post — at most one per post (see V34
 * migration). The question itself is the post's own `body`; this row only
 * carries the auto-close time. Options live in {@link CommunityPostPollOption}.
 */
@Entity
@Table(name = "community_post_poll")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityPostPoll {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "post_id", nullable = false, unique = true)
    private UUID postId;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    @Transient
    public boolean isClosed() {
        return Instant.now().isAfter(closesAt);
    }
}
