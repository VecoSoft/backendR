package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Minimal user-follows-user — powers the Community "Following" feed tab only. */
@Entity
@Table(name = "community_follow", uniqueConstraints =
        @UniqueConstraint(columnNames = {"follower_user_id", "followed_user_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityFollow {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "follower_user_id", nullable = false)
    private UUID followerUserId;

    @Column(name = "followed_user_id", nullable = false)
    private UUID followedUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
