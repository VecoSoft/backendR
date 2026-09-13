package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A business listing mentioned/tagged inside a CommunityPost. A post may mention several businesses. */
@Entity
@Table(name = "community_post_mention", uniqueConstraints =
        @UniqueConstraint(columnNames = {"post_id", "business_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityPostMention {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
